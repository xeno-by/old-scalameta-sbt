/* sbt -- Simple Build Tool
 * Copyright 2010  Mark Harrah
 */
package sbt
package inc

import xsbti.api.{ Source, SourceAPI, Compilation, OutputSetting, _internalOnly_NameHashes }
import xsbti.compile.{ DependencyChanges, Output, SingleOutput, MultipleOutput }
import xsbti.{ Position, Problem, Severity }
import Logger.{ m2o, problem }
import java.io.File
import xsbti.api.Definition

object IncrementalCompile {
  def apply(sources: Set[File], entry: String => Option[File],
    compile: (Set[File], DependencyChanges, xsbti.AnalysisCallback) => Unit,
    previous: Analysis,
    forEntry: File => Option[Analysis],
    output: Output, log: Logger,
    options: IncOptions): (Boolean, Analysis) =
    {
      val current = Stamps.initial(Stamp.lastModified, Stamp.hash, Stamp.lastModified)
      val internalMap = (f: File) => previous.relations.produced(f).headOption
      val externalAPI = getExternalAPI(entry, forEntry)
      try {
        Incremental.compile(sources, entry, previous, current, forEntry, doCompile(compile, internalMap, externalAPI, current, output, options), log, options)
      } catch {
        case e: xsbti.CompileCancelled =>
          log.info("Compilation has been cancelled")
          // in case compilation got cancelled potential partial compilation results (e.g. produced classs files) got rolled back
          // and we can report back as there was no change (false) and return a previous Analysis which is still up-to-date
          (false, previous)
      }
    }
  def doCompile(compile: (Set[File], DependencyChanges, xsbti.AnalysisCallback) => Unit, internalMap: File => Option[File], externalAPI: (File, String) => Option[Source], current: ReadStamps, output: Output, options: IncOptions) =
    (srcs: Set[File], changes: DependencyChanges) => {
      val callback = new AnalysisCallback(internalMap, externalAPI, current, output, options)
      compile(srcs, changes, callback)
      callback.get
    }
  def getExternalAPI(entry: String => Option[File], forEntry: File => Option[Analysis]): (File, String) => Option[Source] =
    (file: File, className: String) =>
      entry(className) flatMap { defines =>
        if (file != Locate.resolve(defines, className))
          None
        else
          forEntry(defines) flatMap { analysis =>
            analysis.relations.definesClass(className).headOption flatMap { src =>
              analysis.apis.internal get src
            }
          }
      }
}
private final class AnalysisCallback(internalMap: File => Option[File], externalAPI: (File, String) => Option[Source], current: ReadStamps, output: Output, options: IncOptions) extends xsbti.AnalysisCallback {
  val compilation = {
    val outputSettings = output match {
      case single: SingleOutput => Array(new OutputSetting("/", single.outputDirectory.getAbsolutePath))
      case multi: MultipleOutput =>
        multi.outputGroups.map(out => new OutputSetting(out.sourceDirectory.getAbsolutePath, out.outputDirectory.getAbsolutePath)).toArray
    }
    new Compilation(System.currentTimeMillis, outputSettings)
  }

  override def toString = (List("APIs", "Binary deps", "Products", "Source deps") zip List(apis, binaryDeps, classes, sourceDeps)).map { case (label, map) => label + "\n\t" + map.mkString("\n\t") }.mkString("\n")

  import collection.mutable.{ HashMap, HashSet, ListBuffer, Map, Set }

  private[this] val apis = new HashMap[File, (Int, SourceAPI)]
  private[this] val usedNames = new HashMap[File, Set[String]]
  private[this] val publicNameHashes = new HashMap[File, _internalOnly_NameHashes]
  private[this] val unreporteds = new HashMap[File, ListBuffer[Problem]]
  private[this] val reporteds = new HashMap[File, ListBuffer[Problem]]
  private[this] val binaryDeps = new HashMap[File, Set[File]]
  // source file to set of generated (class file, class name)
  private[this] val classes = new HashMap[File, Set[(File, String)]]
  // generated class file to its source file
  private[this] val classToSource = new HashMap[File, File]
  // all internal source depenencies, including direct and inherited
  private[this] val sourceDeps = new HashMap[File, Set[File]]
  // inherited internal source dependencies
  private[this] val inheritedSourceDeps = new HashMap[File, Set[File]]
  // external source dependencies:
  //   (internal source, external source depended on, API of external dependency, true if an inheritance dependency, true if dep. comes from a macro expansion)
  private[this] val extSrcDeps = new ListBuffer[(File, String, Source, Boolean, Boolean)]
  private[this] val binaryClassName = new HashMap[File, String]
  // source files containing a macro def.
  private[this] val macroSources = Set[File]()

  private def add[A, B](map: Map[A, Set[B]], a: A, b: B): Unit =
    map.getOrElseUpdate(a, new HashSet[B]) += b

  def problem(category: String, pos: Position, msg: String, severity: Severity, reported: Boolean): Unit =
    {
      for (source <- m2o(pos.sourceFile)) {
        val map = if (reported) reporteds else unreporteds
        map.getOrElseUpdate(source, ListBuffer.empty) += Logger.problem(category, pos, msg, severity)
      }
    }

  def sourceDependency(dependsOn: File, source: File, inherited: Boolean) =
    {
      add(sourceDeps, source, dependsOn)
      if (inherited) add(inheritedSourceDeps, source, dependsOn)
    }
  def externalBinaryDependency(binary: File, className: String, source: File, inherited: Boolean, fromMacro: Boolean) {
    binaryClassName.put(binary, className)
    add(binaryDeps, source, binary)
  }
  def externalSourceDependency(t5: (File, String, Source, Boolean, Boolean)) = extSrcDeps += t5

  def binaryDependency(classFile: File, name: String, source: File, inherited: Boolean, fromMacro: Boolean) =
    internalMap(classFile) match {
      case Some(dependsOn) =>
        // dependency is a product of a source not included in this compilation
        sourceDependency(dependsOn, source, inherited)
      case None =>
        classToSource.get(classFile) match {
          case Some(dependsOn) =>
            // dependency is a product of a source in this compilation step,
            //  but not in the same compiler run (as in javac v. scalac)
            sourceDependency(dependsOn, source, inherited)
          case None =>
            externalDependency(classFile, name, source, inherited, fromMacro)
        }
    }

  private[this] def externalDependency(classFile: File, name: String, source: File, inherited: Boolean, fromMacro: Boolean): Unit =
    externalAPI(classFile, name) match {
      case Some(api) =>
        // dependency is a product of a source in another project
        externalSourceDependency((source, name, api, inherited, fromMacro))
      case None =>
        // dependency is some other binary on the classpath
        externalBinaryDependency(classFile, name, source, inherited, fromMacro)
    }

  def generatedClass(source: File, module: File, name: String) =
    {
      add(classes, source, (module, name))
      classToSource.put(module, source)
    }

  // empty value used when name hashing algorithm is disabled
  private val emptyNameHashes = new xsbti.api._internalOnly_NameHashes(Array.empty, Array.empty)

  def api(sourceFile: File, source: SourceAPI) {
    import xsbt.api.{ APIUtil, HashAPI }
    if (APIUtil.isScalaSourceName(sourceFile.getName) && APIUtil.hasMacro(source)) macroSources += sourceFile
    publicNameHashes(sourceFile) = {
      if (nameHashing)
        (new xsbt.api.NameHashing).nameHashes(source)
      else
        emptyNameHashes
    }
    val shouldMinimize = !Incremental.apiDebug(options)
    val savedSource = if (shouldMinimize) APIUtil.minimize(source) else source
    apis(sourceFile) = (HashAPI(source), savedSource)
  }

  def usedName(sourceFile: File, name: String) = add(usedNames, sourceFile, name)

  def nameHashing: Boolean = options.nameHashing

  def get: Analysis = addUsedNames(addCompilation(addExternals(addBinaries(addProducts(addSources(Analysis.empty(nameHashing = nameHashing)))))))
  def addProducts(base: Analysis): Analysis = addAll(base, classes) { case (a, src, (prod, name)) => a.addProduct(src, prod, current product prod, name) }
  def addBinaries(base: Analysis): Analysis = addAll(base, binaryDeps)((a, src, bin) => a.addBinaryDep(src, bin, binaryClassName(bin), current binary bin))
  def addSources(base: Analysis): Analysis =
    (base /: apis) {
      case (a, (src, api)) =>
        val stamp = current.internalSource(src)
        val hash = stamp match { case h: Hash => h.value; case _ => new Array[Byte](0) }
        // TODO store this in Relations, rather than Source.
        val hasMacro: Boolean = macroSources.contains(src)
        val s = new xsbti.api.Source(compilation, hash, api._2, api._1, publicNameHashes(src), hasMacro)
        val info = SourceInfos.makeInfo(getOrNil(reporteds, src), getOrNil(unreporteds, src))
        val direct = sourceDeps.getOrElse(src, Nil: Iterable[File])
        val publicInherited = inheritedSourceDeps.getOrElse(src, Nil: Iterable[File])
        a.addSource(src, s, stamp, direct, publicInherited, info)
    }
  def getOrNil[A, B](m: collection.Map[A, Seq[B]], a: A): Seq[B] = m.get(a).toList.flatten
  def addExternals(base: Analysis): Analysis = (base /: extSrcDeps) { case (a, (source, name, api, inherited, fromMacro)) => a.addExternalDep(source, name, api, inherited, fromMacro) }
  def addCompilation(base: Analysis): Analysis = base.copy(compilations = base.compilations.add(compilation))
  def addUsedNames(base: Analysis): Analysis = (base /: usedNames) {
    case (a, (src, names)) =>
      (a /: names) { case (a, name) => a.copy(relations = a.relations.addUsedName(src, name)) }
  }

  def addAll[A, B](base: Analysis, m: Map[A, Set[B]])(f: (Analysis, A, B) => Analysis): Analysis =
    (base /: m) {
      case (outer, (a, bs)) =>
        (outer /: bs) { (inner, b) =>
          f(inner, a, b)
        }
    }
}
