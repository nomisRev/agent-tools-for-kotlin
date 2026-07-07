package jarsearch

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.util.Locale
import java.util.jar.JarFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Searches the current classpath state; not worth caching.")
abstract class JarSearchTask : DefaultTask() {
  private lateinit var configurationContainer: ConfigurationContainer
  private lateinit var dependencyHandler: DependencyHandler
  private var catalogExtension: VersionCatalogsExtension? = null
  private lateinit var projectDirectory: File

  fun configureFrom(project: Project) {
    configurationContainer = project.configurations
    dependencyHandler = project.dependencies
    catalogExtension = project.extensions.findByType(VersionCatalogsExtension::class.java)
    projectDirectory = project.projectDir
  }

  @get:Input
  @get:Option(
    option = "dependency",
    description =
      "Dependency to search: '*', a configuration name, a Gradle coordinate " +
        "(group:artifact[:version]), a version-catalog alias, or a direct .jar path.",
  )
  abstract val dependency: Property<String>

  @get:Input
  @get:Optional
  @get:Option(option = "query", description = "Package, type, function, or method query.")
  abstract val query: Property<String>

  @get:Input
  @get:Option(
    option = "kind",
    description =
      "Search kind: all, package, type, function, top_level_function, or method. " +
        "Default: all.",
  )
  abstract val kind: Property<String>

  @get:Input
  @get:Option(
    option = "configuration",
    description = "Gradle configuration to search or use for dependency lookup. Default: compileClasspath.",
  )
  abstract val configuration: Property<String>

  @get:Input
  @get:Option(option = "limit", description = "Maximum results per result section. Default: 20.")
  abstract val limit: Property<Int>

  @get:Input
  @get:Option(option = "include-non-public", description = "Include non-public methods/functions.")
  abstract val includeNonPublic: Property<Boolean>

  @get:Input
  @get:Option(option = "include-synthetic", description = "Include synthetic/compiler-generated methods.")
  abstract val includeSynthetic: Property<Boolean>

  @get:Input
  @get:Option(option = "raw-signatures", description = "Print raw JVM descriptors for methods/functions.")
  abstract val rawSignatures: Property<Boolean>

  @get:Input
  @get:Option(
    option = "transitive",
    description =
      "Transitive search mode: auto, true, or false. Auto searches matching jars first and " +
        "falls back to the selected configuration when a member query has no results.",
  )
  abstract val transitive: Property<String>

  init {
    dependency.convention("*")
    kind.convention("all")
    configuration.convention("compileClasspath")
    limit.convention(20)
    includeNonPublic.convention(false)
    includeSynthetic.convention(false)
    rawSignatures.convention(false)
    transitive.convention("auto")
    outputs.upToDateWhen { false }
  }

  @TaskAction
  fun search() {
    val searchKind = SearchKind.parse(kind.get())
    val queryValue = query.orNull?.trim()?.takeUnless { it.isEmpty() }
    val maxResults = limit.get().coerceAtLeast(1)
    val transitiveMode = TransitiveMode.parse(transitive.get())

    searchKind.requireQueryIfNeeded(queryValue)

    val selected = resolveJars(dependency.get().trim(), configuration.get().trim(), transitiveMode)
    val primaryJars = selected.primary.distinctBy { it.canonicalFile }
    val fallbackJars = selected.fallback.distinctBy { it.canonicalFile }.filterNot { fallback ->
      primaryJars.any { it.canonicalFile == fallback.canonicalFile }
    }

    if (primaryJars.isEmpty()) {
      throw GradleException("No jar files found for '${dependency.get()}' in '${configuration.get()}'.")
    }

    logger.quiet(
      "Searching ${primaryJars.size} jar(s) for dependency '${dependency.get()}' " +
        "(kind=$searchKind, query=${queryValue ?: "<none>"})",
    )

    val options =
      SearchOptions(
        kind = searchKind,
        query = queryValue,
        limit = maxResults,
        includeNonPublic = includeNonPublic.get(),
        includeSynthetic = includeSynthetic.get(),
        rawSignatures = rawSignatures.get(),
      )

    val primaryOutput = searchJars(primaryJars, options)
    if (primaryOutput.hasResults || fallbackJars.isEmpty() || !selected.shouldFallback(searchKind, queryValue)) {
      printOutput(primaryOutput)
      return
    }

    logger.quiet(
      "No member matches in primary jar(s); searching ${fallbackJars.size} transitive/configuration jar(s).",
    )
    val fallbackOutput = searchJars(fallbackJars, options)
    printOutput(fallbackOutput)
  }

  private fun resolveJars(
    dependencyNotation: String,
    configurationName: String,
    transitiveMode: TransitiveMode,
  ): SelectedJars {
    val configuration =
      configurationContainer.findByName(configurationName)
        ?: throw GradleException("Configuration '$configurationName' was not found.")
    val configurationArtifacts = configuration.resolvedConfiguration.lenientConfiguration.artifacts.toList()
    val configurationJars = configurationArtifacts.map { it.file }.filter { it.isJarFile }

    if (dependencyNotation == "*" || dependencyNotation == configurationName) {
      return SelectedJars(configurationJars, emptyList(), fallbackOnMemberMiss = false)
    }

    val directFile = dependencyNotation.asProjectFile()
    if (directFile.isJarFile) {
      return SelectedJars(listOf(directFile), emptyList(), fallbackOnMemberMiss = false)
    }

    configurationContainer.findByName(dependencyNotation)?.let { namedConfiguration ->
      val jars = namedConfiguration.resolve().filter { it.isJarFile }
      return SelectedJars(jars, emptyList(), fallbackOnMemberMiss = false)
    }

    val module = findCatalogModule(dependencyNotation) ?: parseCoordinate(dependencyNotation)
    if (module != null) {
      val matchingJars =
        configurationArtifacts
          .filter { artifact ->
            artifact.moduleVersion.id.group == module.group &&
              artifact.moduleVersion.id.name.matchesRequestedModuleName(module.name) &&
              (module.version == null || artifact.moduleVersion.id.version == module.version)
          }
          .map { it.file }
          .filter { it.isJarFile }

      if (matchingJars.isNotEmpty()) {
        val primary = if (transitiveMode == TransitiveMode.TRUE) configurationJars else matchingJars
        val fallback = if (transitiveMode == TransitiveMode.AUTO) configurationJars else emptyList()
        return SelectedJars(primary, fallback, fallbackOnMemberMiss = transitiveMode == TransitiveMode.AUTO)
      }

      if (module.version != null) {
        val detachedDependency = dependencyHandler.create(module.notation)
        val detached = configurationContainer.detachedConfiguration(detachedDependency)
        detached.isTransitive = transitiveMode == TransitiveMode.TRUE
        val detachedJars = detached.resolve().filter { it.isJarFile }
        val fallback = if (transitiveMode == TransitiveMode.AUTO) configurationJars else emptyList()
        return SelectedJars(detachedJars, fallback, fallbackOnMemberMiss = transitiveMode == TransitiveMode.AUTO)
      }
    }

    throw GradleException(
      "Could not resolve '$dependencyNotation'. Use '*', a configuration name, a .jar path, " +
        "a version-catalog alias, or group:artifact[:version].",
    )
  }

  private fun findCatalogModule(alias: String): ModuleCoordinate? {
    val catalogs = catalogExtension ?: return null

    for (catalogName in catalogs.catalogNames) {
      val catalog = catalogs.named(catalogName)
      val unprefixedAlias = alias.removeCatalogPrefix(catalogName)
      for (candidate in catalogAliasCandidates(unprefixedAlias)) {
        val dependencyProvider = catalog.findLibrary(candidate)
        if (dependencyProvider.isPresent) {
          val dependency = dependencyProvider.get().get()
          return dependency.toModuleCoordinate()
        }
      }
    }
    return null
  }

  private fun String.removeCatalogPrefix(catalogName: String): String =
    removePrefix("$catalogName.").removePrefix("$catalogName-")

  private fun catalogAliasCandidates(alias: String): List<String> =
    listOf(
        alias,
        alias.replace('.', '-'),
        alias.replace('_', '-'),
        alias.replace('.', '-').replace('_', '-'),
      )
      .distinct()

  private fun MinimalExternalModuleDependency.toModuleCoordinate(): ModuleCoordinate =
    ModuleCoordinate(module.group, module.name, versionConstraint.requiredVersion.ifBlank { null })

  private fun parseCoordinate(value: String): ModuleCoordinate? {
    val parts = value.split(':')
    if (parts.size !in 2..3 || parts.any { it.isBlank() }) return null
    return ModuleCoordinate(parts[0], parts[1], parts.getOrNull(2))
  }

  private fun String.matchesRequestedModuleName(requestedName: String): Boolean =
    this == requestedName || this == "$requestedName-jvm"

  private fun String.asProjectFile(): File {
    val file = File(this)
    return if (file.isAbsolute) file else File(projectDirectory, this)
  }

  private fun printOutput(output: SearchOutput) {
    if (!output.hasResults) {
      logger.quiet("No matches found.")
      return
    }
    output.sections.filter { it.lines.isNotEmpty() }.forEach { section ->
      logger.quiet("\n${section.title} (${section.total} match${if (section.total == 1) "" else "es"})")
      section.lines.forEach { logger.quiet("  $it") }
      if (section.total > section.lines.size) {
        logger.quiet("  ... ${section.total - section.lines.size} more; increase --limit to show more")
      }
    }
  }
}

private data class ModuleCoordinate(val group: String, val name: String, val version: String?) {
  val notation: String
    get() = if (version == null) "$group:$name" else "$group:$name:$version"
}

private data class SelectedJars(
  val primary: List<File>,
  val fallback: List<File>,
  val fallbackOnMemberMiss: Boolean,
) {
  fun shouldFallback(kind: SearchKind, query: String?): Boolean =
    fallbackOnMemberMiss && query != null && kind.searchesMembers
}

private enum class TransitiveMode {
  AUTO,
  TRUE,
  FALSE,
  ;

  companion object {
    fun parse(value: String): TransitiveMode =
      when (value.lowercase(Locale.ROOT)) {
        "auto" -> AUTO
        "true", "yes", "y" -> TRUE
        "false", "no", "n" -> FALSE
        else -> throw GradleException("Unknown --transitive value '$value'. Use auto, true, or false.")
      }
  }
}

private enum class SearchKind(val searchesMembers: Boolean) {
  ALL(searchesMembers = true),
  PACKAGE(searchesMembers = false),
  TYPE(searchesMembers = false),
  FUNCTION(searchesMembers = true),
  TOP_LEVEL_FUNCTION(searchesMembers = true),
  METHOD(searchesMembers = true),
  ;

  fun requireQueryIfNeeded(query: String?) {
    if (query == null && this in setOf(FUNCTION, TOP_LEVEL_FUNCTION, METHOD)) {
      throw GradleException("--query is required for kind '$this'.")
    }
  }

  override fun toString(): String = name.lowercase(Locale.ROOT)

  companion object {
    fun parse(value: String): SearchKind =
      when (value.lowercase(Locale.ROOT).replace('-', '_')) {
        "all" -> ALL
        "package", "packages" -> PACKAGE
        "type", "types", "class", "classes" -> TYPE
        "function", "functions" -> FUNCTION
        "top_level_function", "top_level_functions", "toplevel_function", "toplevel_functions" ->
          TOP_LEVEL_FUNCTION
        "method", "methods" -> METHOD
        else ->
          throw GradleException(
            "Unknown --kind '$value'. Use all, package, type, function, top_level_function, or method.",
          )
      }
  }
}

private data class SearchOptions(
  val kind: SearchKind,
  val query: String?,
  val limit: Int,
  val includeNonPublic: Boolean,
  val includeSynthetic: Boolean,
  val rawSignatures: Boolean,
)

private data class SearchOutput(val sections: List<ResultSection>) {
  val hasResults: Boolean = sections.any { it.total > 0 }
}

private data class ResultSection(val title: String, val total: Int, val lines: List<String>)

private fun searchJars(jars: List<File>, options: SearchOptions): SearchOutput {
  val jarIndexes = jars.map { JarIndex.read(it) }
  val sections = mutableListOf<ResultSection>()

  if (options.kind == SearchKind.ALL || options.kind == SearchKind.PACKAGE) {
    sections += packageSection(jarIndexes, options)
  }

  if (options.kind == SearchKind.ALL || options.kind == SearchKind.TYPE) {
    sections += typeSection(jarIndexes, options)
  }

  if (options.kind == SearchKind.ALL || options.kind == SearchKind.TYPE || options.kind.searchesMembers) {
    val exactTypes = exactTypeMatches(jarIndexes, options.query)
    if ((options.kind == SearchKind.ALL || options.kind == SearchKind.TYPE) && exactTypes.isNotEmpty()) {
      sections += declaredMembersSection(exactTypes, options)
    } else if (options.query != null && (options.kind == SearchKind.ALL || options.kind.searchesMembers)) {
      sections += memberSection(jarIndexes, options)
    }
  }

  return SearchOutput(sections)
}

private fun packageSection(jarIndexes: List<JarIndex>, options: SearchOptions): ResultSection {
  val matches =
    jarIndexes
      .flatMap { index -> index.packages.map { packageName -> "$packageName [${index.jar.name}]" } }
      .filter { line -> options.query == null || line.contains(options.query, ignoreCase = true) }
      .distinct()
      .sorted()
  return ResultSection("Packages", matches.size, matches.take(options.limit))
}

private fun typeSection(jarIndexes: List<JarIndex>, options: SearchOptions): ResultSection {
  val exactTypes = exactTypeMatches(jarIndexes, options.query)
  val matches =
    if (exactTypes.isNotEmpty()) exactTypes
    else
      jarIndexes
        .flatMap { index -> index.classes.map { classEntry -> classEntry.withJar(index.jar) } }
        .filter { result -> options.query == null || result.matches(options.query) }
        .sortedBy { it.display }

  val lines = matches.map { it.display }.distinct()
  return ResultSection("Types", lines.size, lines.take(options.limit))
}

private fun exactTypeMatches(jarIndexes: List<JarIndex>, query: String?): List<ClassSearchResult> {
  if (query == null) return emptyList()
  val normalizedQuery = query.normalizeTypeQuery()
  return jarIndexes
    .flatMap { index -> index.classes.map { classEntry -> classEntry.withJar(index.jar) } }
    .filter { result -> result.entry.fqn == normalizedQuery || result.entry.simpleName == normalizedQuery }
    .sortedBy { it.display }
}

private fun declaredMembersSection(
  exactTypes: List<ClassSearchResult>,
  options: SearchOptions,
): ResultSection {
  val members =
    exactTypes
      .groupBy { it.jar }
      .flatMap { (jar, results) ->
        JarFile(jar).use { jarFile ->
          results.flatMap { result ->
            result.entry
              .readClassInfo(jarFile)
              .methods
              .filter { method -> method.isDisplayable(options) }
              .map { method -> method.format(result.entry.fqn, options.rawSignatures) }
          }
        }
      }
  return ResultSection("Declared members", members.size, members.take(options.limit))
}

private fun memberSection(jarIndexes: List<JarIndex>, options: SearchOptions): ResultSection {
  val query = options.query ?: return ResultSection("Members", 0, emptyList())
  val matches =
    jarIndexes.flatMap { index ->
      JarFile(index.jar).use { jarFile ->
        index.classes.flatMap { classEntry ->
          val classInfo = classEntry.readClassInfo(jarFile)
          classInfo.methods
            .filter { it.isDisplayable(options) }
            .filter { method -> method.name.contains(query, ignoreCase = true) }
            .filter { method ->
              when (options.kind) {
                SearchKind.TOP_LEVEL_FUNCTION -> classEntry.isKotlinFileFacade && method.isStatic
                SearchKind.METHOD -> true
                SearchKind.FUNCTION,
                SearchKind.ALL -> true
                SearchKind.PACKAGE,
                SearchKind.TYPE -> false
              }
            }
            .map { method -> method.format(classEntry.fqn, options.rawSignatures) + " [${index.jar.name}]" }
        }
      }
    }
  val title =
    when (options.kind) {
      SearchKind.TOP_LEVEL_FUNCTION -> "Top-level functions"
      SearchKind.FUNCTION -> "Functions/methods"
      SearchKind.METHOD -> "Methods"
      else -> "Members"
    }
  return ResultSection(title, matches.size, matches.take(options.limit))
}

private data class JarIndex(val jar: File, val classes: List<ClassEntry>, val packages: List<String>) {
  companion object {
    fun read(jar: File): JarIndex {
      JarFile(jar).use { jarFile ->
        val classes =
          jarFile
            .entries()
            .asSequence()
            .map { it.name }
            .filter { it.endsWith(".class") }
            .filterNot { it == "module-info.class" || it.endsWith("/module-info.class") }
            .filterNot { it.startsWith("META-INF/") }
            .map { classEntryName -> ClassEntry(classEntryName.removeSuffix(".class")) }
            .toList()
        val packages = classes.mapNotNull { it.packageName.takeUnless(String::isEmpty) }.distinct().sorted()
        return JarIndex(jar, classes, packages)
      }
    }
  }
}

private data class ClassEntry(val internalName: String) {
  val fqn: String = internalName.replace('/', '.').replace('$', '.')
  val simpleName: String = fqn.substringAfterLast('.')
  val packageName: String = internalName.substringBeforeLast('/', missingDelimiterValue = "").replace('/', '.')
  val isKotlinFileFacade: Boolean = simpleName.endsWith("Kt") || simpleName.contains("Kt__")

  fun withJar(jar: File): ClassSearchResult = ClassSearchResult(this, jar)

  fun readClassInfo(jarFile: JarFile): ClassInfo {
    val entry = jarFile.getJarEntry("$internalName.class") ?: throw EOFException("Missing $internalName.class")
    return jarFile.getInputStream(entry).use { input ->
      ClassFileParser.parse(DataInputStream(BufferedInputStream(input)))
    }
  }
}

private data class ClassSearchResult(val entry: ClassEntry, val jar: File) {
  val display: String = "${entry.fqn} [${jar.name}]"

  fun matches(query: String): Boolean {
    val normalizedQuery = query.normalizeTypeQuery()
    return entry.fqn.contains(normalizedQuery, ignoreCase = true) ||
      entry.simpleName.contains(normalizedQuery, ignoreCase = true)
  }
}

private data class ClassInfo(val methods: List<MethodInfo>)

private data class MethodInfo(
  val accessFlags: Int,
  val name: String,
  val descriptor: String,
  val signature: String?,
  val syntheticAttribute: Boolean,
) {
  val isPublic: Boolean = accessFlags.hasFlag(ACC_PUBLIC)
  val isStatic: Boolean = accessFlags.hasFlag(ACC_STATIC)
  private val isBridge: Boolean = accessFlags.hasFlag(ACC_BRIDGE)
  private val isSynthetic: Boolean = accessFlags.hasFlag(ACC_SYNTHETIC) || syntheticAttribute

  fun isDisplayable(options: SearchOptions): Boolean =
    name != "<init>" &&
      name != "<clinit>" &&
      (options.includeNonPublic || isPublic) &&
      (options.includeSynthetic || (!isBridge && !isSynthetic && !looksCompilerGenerated(name)))

  fun format(ownerFqn: String, raw: Boolean): String {
    if (raw) return "${accessText()} fun $ownerFqn.$name$descriptor${signature?.let { " signature=$it" } ?: ""}"
    val parsed = MethodDescriptor.parse(descriptor)
    return "${accessText()} fun $ownerFqn.$name(${parsed.parameters.joinToString()})${parsed.returnSuffix}"
  }

  private fun accessText(): String {
    val visibility =
      when {
        accessFlags.hasFlag(ACC_PUBLIC) -> "public"
        accessFlags.hasFlag(ACC_PROTECTED) -> "protected"
        accessFlags.hasFlag(ACC_PRIVATE) -> "private"
        else -> "package-private"
      }
    val modifiers = buildList {
      add(visibility)
      if (accessFlags.hasFlag(ACC_STATIC)) add("static")
      if (accessFlags.hasFlag(ACC_ABSTRACT)) add("abstract")
      if (accessFlags.hasFlag(ACC_FINAL)) add("final")
    }
    return modifiers.joinToString(" ")
  }
}

private fun looksCompilerGenerated(name: String): Boolean =
  name.endsWith("\$default") ||
    name.endsWith("\$annotations") ||
    name.startsWith("access\$") ||
    name.contains('$')

private data class MethodDescriptor(val parameters: List<String>, val returnType: String) {
  val returnSuffix: String = if (returnType == "Unit") "" else ": $returnType"

  companion object {
    fun parse(descriptor: String): MethodDescriptor {
      var index = 0
      require(descriptor[index] == '(') { "Not a method descriptor: $descriptor" }
      index++
      val parameters = mutableListOf<String>()
      while (descriptor[index] != ')') {
        val parsed = parseType(descriptor, index)
        parameters += parsed.type
        index = parsed.nextIndex
      }
      index++
      val returnType = parseType(descriptor, index).type
      return MethodDescriptor(parameters, returnType)
    }

    private fun parseType(descriptor: String, startIndex: Int): ParsedType {
      var index = startIndex
      var dimensions = 0
      while (descriptor[index] == '[') {
        dimensions++
        index++
      }
      val base =
        when (val marker = descriptor[index]) {
          'V' -> "Unit"
          'Z' -> "Boolean"
          'B' -> "Byte"
          'C' -> "Char"
          'S' -> "Short"
          'I' -> "Int"
          'J' -> "Long"
          'F' -> "Float"
          'D' -> "Double"
          'L' -> {
            val end = descriptor.indexOf(';', index)
            descriptor.substring(index + 1, end).replace('/', '.').replace('$', '.').simplifyJavaLang()
          }
          else -> throw GradleException("Unsupported descriptor marker '$marker' in $descriptor")
        }
      if (descriptor[index] == 'L') index = descriptor.indexOf(';', index)
      val type = buildString {
        append(base)
        repeat(dimensions) { append("[]") }
      }
      return ParsedType(type, index + 1)
    }
  }
}

private data class ParsedType(val type: String, val nextIndex: Int)

private fun String.simplifyJavaLang(): String =
  when (this) {
    "java.lang.Object" -> "Any"
    "java.lang.String" -> "String"
    "java.lang.Boolean" -> "Boolean"
    "java.lang.Byte" -> "Byte"
    "java.lang.Character" -> "Char"
    "java.lang.Short" -> "Short"
    "java.lang.Integer" -> "Int"
    "java.lang.Long" -> "Long"
    "java.lang.Float" -> "Float"
    "java.lang.Double" -> "Double"
    "java.lang.Void" -> "Unit"
    else -> this
  }

private object ClassFileParser {
  fun parse(input: DataInputStream): ClassInfo {
    val magic = input.readInt()
    if (magic != 0xCAFEBABE.toInt()) throw GradleException("Invalid class file")
    input.readUnsignedShort() // minor
    input.readUnsignedShort() // major

    val constantPool = ConstantPool.read(input)

    input.readUnsignedShort() // access flags
    input.readUnsignedShort() // this class
    input.readUnsignedShort() // super class

    repeat(input.readUnsignedShort()) { input.readUnsignedShort() } // interfaces

    repeat(input.readUnsignedShort()) { skipMember(input) } // fields

    val methods =
      List(input.readUnsignedShort()) {
        val methodAccess = input.readUnsignedShort()
        val name = constantPool.utf8(input.readUnsignedShort())
        val descriptor = constantPool.utf8(input.readUnsignedShort())
        var signature: String? = null
        var syntheticAttribute = false
        repeat(input.readUnsignedShort()) {
          val attributeName = constantPool.utf8(input.readUnsignedShort())
          val length = input.readInt()
          when (attributeName) {
            "Signature" -> {
              signature = constantPool.utf8(input.readUnsignedShort())
              if (length > 2) input.skipFully(length - 2)
            }
            "Synthetic" -> {
              syntheticAttribute = true
              input.skipFully(length)
            }
            else -> input.skipFully(length)
          }
        }
        MethodInfo(methodAccess, name, descriptor, signature, syntheticAttribute)
      }

    return ClassInfo(methods)
  }

  private fun skipMember(input: DataInputStream) {
    input.readUnsignedShort() // access
    input.readUnsignedShort() // name
    input.readUnsignedShort() // descriptor
    repeat(input.readUnsignedShort()) {
      input.readUnsignedShort() // attribute name
      input.skipFully(input.readInt())
    }
  }
}

private class ConstantPool(
  private val utf8Entries: Array<String?>,
) {
  fun utf8(index: Int): String = utf8Entries[index] ?: throw GradleException("Constant pool entry $index is not UTF-8")

  companion object {
    fun read(input: DataInputStream): ConstantPool {
      val count = input.readUnsignedShort()
      val utf8Entries = arrayOfNulls<String>(count)
      var index = 1
      while (index < count) {
        when (val tag = input.readUnsignedByte()) {
          1 -> utf8Entries[index] = input.readUTF()
          3,
          4 -> input.skipFully(4)
          5,
          6 -> {
            input.skipFully(8)
            index++
          }
          7,
          8,
          16,
          19,
          20 -> input.skipFully(2)
          9,
          10,
          11,
          12,
          17,
          18 -> input.skipFully(4)
          15 -> input.skipFully(3)
          else -> throw GradleException("Unsupported constant-pool tag $tag")
        }
        index++
      }
      return ConstantPool(utf8Entries)
    }
  }
}

private fun DataInputStream.skipFully(bytes: Int) {
  var remaining = bytes
  while (remaining > 0) {
    val skipped = skipBytes(remaining)
    if (skipped <= 0) throw EOFException("Could not skip $bytes bytes ($remaining remaining)")
    remaining -= skipped
  }
}

private fun Int.hasFlag(flag: Int): Boolean = (this and flag) != 0

private val File.isJarFile: Boolean
  get() = isFile && extension.equals("jar", ignoreCase = true)

/** Normalizes JVM-style nested-class queries (`Outer$Inner`) to the dotted form used by [ClassEntry.fqn]. */
private fun String.normalizeTypeQuery(): String = replace('$', '.')

private const val ACC_PUBLIC = 0x0001
private const val ACC_PRIVATE = 0x0002
private const val ACC_PROTECTED = 0x0004
private const val ACC_STATIC = 0x0008
private const val ACC_FINAL = 0x0010
private const val ACC_BRIDGE = 0x0040
private const val ACC_SYNTHETIC = 0x1000
private const val ACC_ABSTRACT = 0x0400
