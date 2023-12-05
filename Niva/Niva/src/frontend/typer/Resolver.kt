package frontend.typer

import codogen.GeneratorKt
import codogen.addToGradleDependencies
import codogen.collectAllGenericsFromBranches
import frontend.meta.Position
import frontend.meta.Token
import frontend.meta.TokenType
import frontend.meta.compileError
import frontend.parser.parsing.MessageDeclarationType
import frontend.parser.types.ast.*
import frontend.util.div
import frontend.util.removeDoubleQuotes
import main.frontend.typer.resolveMessageDeclaration
import java.io.File

typealias TypeName = String


@Suppress("NOTHING_TO_INLINE")
inline fun createDefaultType(type: InternalTypes): Pair<InternalTypes, Type.InternalType> {
    return type to Type.InternalType(
        typeName = type,
        pkg = "common",
    )
}

typealias PkgToUnresolvedDecl<T> = MutableMap<String, MutableSet<T>>

fun <T> PkgToUnresolvedDecl<T>.add(pkg: String, decl: T) {
    val q = this[pkg]
    if (q == null) {
        this[pkg] = mutableSetOf(decl)
    } else {
        q.add(decl)
    }
}
fun <T> PkgToUnresolvedDecl<T>.remove(pkg: String, decl: T) {
    this[pkg]?.remove(decl)
}


@Suppress("UNUSED_VARIABLE")
class Resolver(
    val projectName: String,

    var statements: MutableList<Statement>,

    val mainFile: File,
    val otherFilesPaths: List<File> = listOf(),

    val projects: MutableMap<String, Project> = mutableMapOf(),

    // reload when package changed
    val typeTable: MutableMap<TypeName, Type> = mutableMapOf(),
    val typeDB: TypeDB = TypeDB(),

    var topLevelStatements: MutableList<Statement> = mutableListOf(),
    var currentLevel: Int = 0,

    var currentProjectName: String = "common",
    var currentPackageName: String = "common",
    var currentProtocolName: String = "common",

    // number when resolving lambda or keyword
    var currentArgumentNumber: Int = -1,

    // for recursive types
    val unResolvedMessageDeclarations: PkgToUnresolvedDecl<MessageDeclaration>  = mutableMapOf(),
    val unResolvedTypeDeclarations: PkgToUnresolvedDecl<SomeTypeDeclaration> = mutableMapOf(),
    var allDeclarationResolvedAlready: Boolean = false,

    val generator: GeneratorKt = GeneratorKt(),

    var compilationTarget: CompilationTarget = CompilationTarget.jvm,
    var compilationMode: CompilationMode = CompilationMode.debug,

    // set to null before body resolve, check after to know was there return or not
    var wasThereReturn: Type? = null
) {
    companion object {

        val defaultTypes: Map<InternalTypes, Type.InternalType> = mapOf(

            createDefaultType(InternalTypes.Int),
            createDefaultType(InternalTypes.String),
            createDefaultType(InternalTypes.Char),
            createDefaultType(InternalTypes.Float),
            createDefaultType(InternalTypes.Boolean),
            createDefaultType(InternalTypes.Unit),

            createDefaultType(InternalTypes.Project),
            createDefaultType(InternalTypes.Bind),
            createDefaultType(InternalTypes.IntRange),

            createDefaultType(InternalTypes.Any),
            createDefaultType(InternalTypes.Nothing),
        )

        init {
            val intType = defaultTypes[InternalTypes.Int]!!
            val stringType = defaultTypes[InternalTypes.String]!!
            val charType = defaultTypes[InternalTypes.Char]!!
            val floatType = defaultTypes[InternalTypes.Float]!!
            val boolType = defaultTypes[InternalTypes.Boolean]!!
            val unitType = defaultTypes[InternalTypes.Unit]!!
            val intRangeType = defaultTypes[InternalTypes.IntRange]!!
            val anyType = defaultTypes[InternalTypes.Any]!!

            intType.protocols.putAll(
                createIntProtocols(
                    intType = intType,
                    stringType = stringType,
                    unitType = unitType,
                    boolType = boolType,
                    floatType = floatType,
                    intRangeType = intRangeType,
                    anyType = anyType,
                    charType = charType
                )
            )

            floatType.protocols.putAll(
                createFloatProtocols(
                    intType = intType,
                    stringType = stringType,
                    unitType = unitType,
                    boolType = boolType,
                    floatType = floatType,
                    intRangeType = intRangeType,
                    anyType = anyType
                )
            )

            stringType.protocols.putAll(
                createStringProtocols(
                    intType = intType,
                    stringType = stringType,
                    unitType = unitType,
                    boolType = boolType,
                    charType = charType,
                    any = anyType,
                    floatType = floatType
                )
            )

            boolType.protocols.putAll(
                createBoolProtocols(
                    intType = intType,
                    stringType = stringType,
                    unitType = unitType,
                    boolType = boolType,
                    any = anyType,
                    genericParam = Type.UnknownGenericType("T")
                )
            )

            charType.protocols.putAll(
                createCharProtocols(
                    intType = intType,
                    stringType = stringType,
                    unitType = unitType,
                    boolType = boolType,
                    charType = charType,
                    any = anyType
                )
            )


            anyType.protocols.putAll(
                createAnyProtocols(
                    unitType = unitType,
                    any = anyType
                )
            )

            intRangeType.protocols.putAll(
                createIntRangeProtocols(
                    rangeType = intRangeType,
                    boolType = boolType,
                    intType = intType,
                    unitType = unitType,
                    any = anyType
                )
            )

        }

    }

    init {
        // Default types
        val intType = defaultTypes[InternalTypes.Int]!!
        val stringType = defaultTypes[InternalTypes.String]!!
        val charType = defaultTypes[InternalTypes.Char]!!
        val floatType = defaultTypes[InternalTypes.Float]!!
        val boolType = defaultTypes[InternalTypes.Boolean]!!
        val unitType = defaultTypes[InternalTypes.Unit]!!
        val intRangeType = defaultTypes[InternalTypes.IntRange]!!
        val anyType = defaultTypes[InternalTypes.Any]!!
        val nothingType = defaultTypes[InternalTypes.Nothing]!!
        val genericType = Type.UnknownGenericType("T")
        val differentGenericType = Type.UnknownGenericType("G")

        /// Default packages
        val commonProject = Project("common")
        val corePackage = Package("core")
        val mainPackage = Package(MAIN_PKG_NAME)

        // pkg with everything that was declared without package specification
        commonProject.packages["common"] = Package("common")
        // pkg with std types like Int
        commonProject.packages["core"] = corePackage
        // package with main function
        commonProject.packages[MAIN_PKG_NAME] = mainPackage


        /////init basic types/////
        defaultTypes.forEach { (k, v) ->
            typeTable[k.name] = v // fixed
            typeDB.addInternalType(k.name, v)
        }
        // add all default types to core package
        defaultTypes.forEach { (k, v) ->
            corePackage.types[k.name] = v
        }

        ///add collections///
        // List
        val listType = Type.UserType(
            name = "List",
            typeArgumentList = listOf(genericType),
            fields = mutableListOf(),
            pkg = "core",
        )
        val listTypeOfDifferentGeneric = Type.UserType(
            name = "List",
            typeArgumentList = listOf(differentGenericType),
            fields = mutableListOf(),
            pkg = "core",
        )
        listType.protocols.putAll(
            createListProtocols(
                intType = intType,
                unitType = unitType,
                boolType = boolType,
                listType = listType,
                listTypeOfDifferentGeneric = listTypeOfDifferentGeneric,
                itType = genericType,
                differentGenericType = differentGenericType
            )
        )
        listTypeOfDifferentGeneric.protocols.putAll(listType.protocols)

        typeTable[listType.name] = listType// fixed
        typeDB.addUserLike(listType.name, listType, createFakeToken())
        corePackage.types[listType.name] = listType
        // Mutable list
        val mutableListType = Type.UserType(
            name = "MutableList",
            typeArgumentList = listOf(genericType),
            fields = mutableListOf(),
            pkg = "core",
        )
        val mutListTypeOfDifferentGeneric = Type.UserType(
            name = "MutableList",
            typeArgumentList = listOf(differentGenericType),
            fields = mutableListOf(),
            pkg = "core",
        )
        mutableListType.protocols.putAll(
            createListProtocols(
                intType = intType,
                unitType = unitType,
                boolType = boolType,
                listType = mutableListType,
                listTypeOfDifferentGeneric = mutListTypeOfDifferentGeneric,
                itType = genericType,
                differentGenericType = differentGenericType
            )
        )
        mutListTypeOfDifferentGeneric.protocols.putAll(mutableListType.protocols)
        typeTable[mutableListType.name] = mutableListType// fixed
        typeDB.addUserLike(mutableListType.name, mutableListType, createFakeToken())

        corePackage.types[mutableListType.name] = mutableListType

        // mutable set
        val mutableSetType = Type.UserType(
            name = "MutableSet",
            typeArgumentList = listOf(genericType),
            fields = mutableListOf(),
            pkg = "core",
        )
        val mutSetTypeOfDifferentGeneric = Type.UserType(
            name = "MutableSet",
            typeArgumentList = listOf(differentGenericType),
            fields = mutableListOf(),
            pkg = "core",
        )
        mutableSetType.protocols.putAll(
            createSetProtocols(
                intType = intType,
                unitType = unitType,
                boolType = boolType,
                setType = mutableSetType,
                setTypeOfDifferentGeneric = mutSetTypeOfDifferentGeneric,
                genericTypeOfSetElements = genericType,
                differentGenericType = differentGenericType
            )
        )
        mutSetTypeOfDifferentGeneric.protocols.putAll(mutableSetType.protocols)
        typeTable[mutableSetType.name] = mutableSetType// fixed
        typeDB.addUserLike(mutableSetType.name, mutableSetType, createFakeToken())

        corePackage.types[mutableSetType.name] = mutableSetType

        // mutable map
        val mapType = Type.UserType(
            name = "MutableMap",
            typeArgumentList = listOf(genericType, differentGenericType),
            fields = mutableListOf(),
            pkg = "core",
        )
        val mapTypeOfDifferentGeneric = Type.UserType(
            name = "MutableMap",
            typeArgumentList = listOf(differentGenericType),
            fields = mutableListOf(),
            pkg = "core",
        )
        mapType.protocols.putAll(
            createMapProtocols(
                intType = intType,
                unitType = unitType,
                boolType = boolType,
                mapType = mapType,
                mapTypeOfDifferentGeneric = listTypeOfDifferentGeneric,
                keyType = genericType,
                valueType = differentGenericType,
                setType = mutableSetType,
                listType = listType
            )
        )

        mapTypeOfDifferentGeneric.protocols.putAll(mapType.protocols)
        typeTable[mapType.name] = mapType// fixed
        typeDB.addUserLike(mapType.name, mapType, createFakeToken())

        corePackage.types[mapType.name] = mapType


//        val kotlinPkg = Package("kotlin", isBinding = true)
//        commonProject.packages["kotlin"] = kotlinPkg

        /// add Error
        val errorType = Type.UserType(
            name = "Error",
            typeArgumentList = listOf(),
            fields = mutableListOf(TypeField("message", stringType)),
            pkg = "core",
        )
        errorType.isBinding = true
        errorType.protocols.putAll(
            createExceptionProtocols(
                errorType,
                unitType,
                nothingType,
                stringType
            )
        )
//        kotlinPkg.types["Exception"] = exceptionType


        typeTable[errorType.name] = errorType// fixed
        typeDB.addUserLike(errorType.name, errorType, createFakeToken())

        corePackage.types[errorType.name] = errorType

        projects[projectName] = commonProject

    }
}

fun Resolver.resolveDeclarationsOnly(statements: List<Statement>) {
    statements.forEach {
        if (it is Declaration) {
//            changePackage(savedPackageName, createFakeToken())
            resolveDeclarations(it, mutableMapOf(), resolveBody = false)
        }
        if (it is MessageSendKeyword) {
            when (it.receiver.str) {
                "Project" ->
                    resolveProjectKeyMessage(it)

                "Bind" -> {
                    val savedPackageName = currentPackageName

                    val msg = it.messages[0]
                    if (msg !is KeywordMsg)
                        it.token.compileError("Bind must have keyword message")
                    if (msg.args.count() < 2)
                        it.token.compileError("Bind must have at least 2 argument: package and content")
                    val pkgArg = msg.args.find { it.name == "package" }
                    if (pkgArg == null)
                        msg.token.compileError("'package' param is missing")
                    val contentArg = msg.args.find { it.name == "content" }
                    if (contentArg == null)
                        msg.token.compileError("'content' param is missing")


                    if (pkgArg.keywordArg !is LiteralExpression)
                        pkgArg.keywordArg.token.compileError("Package argument must be a string")
                    if (contentArg.keywordArg !is CodeBlock)
                        contentArg.keywordArg.token.compileError("Content argument must be a code block with type and method declarations")


                    val pkgName = pkgArg.keywordArg.toString()

                    changePackage(pkgName, it.token, true)
                    val declarations = contentArg.keywordArg.statements
                    declarations.forEach { decl ->
                        if (decl is Declaration) {
                            resolveDeclarations(decl, mutableMapOf(), resolveBody = false)
                        } else {
                            decl.token.compileError("There can be only declarations inside Bind, but found $decl")
                        }
                    }


                    val gettersArg = msg.args.find { it.name == "getters" }
                    if (gettersArg != null) {
                        if (gettersArg.keywordArg !is CodeBlock)
                            gettersArg.keywordArg.token.compileError("Getter argument must be a code block with type and method declarations")
                        val gettersDeclarations = gettersArg.keywordArg.statements
                        gettersDeclarations.forEach { getter ->

                            if (getter !is MessageDeclarationUnary) {
                                getter.token.compileError("Unary declaration expected inside getters block")
                            }
                            addNewUnaryMessage(getter, isGetter = true)

                        }
                    }

                    changePackage(savedPackageName, createFakeToken())
                }
            }
        }

    }
//    changePackage(savedPackageName, createFakeToken())
}

// нужен механизм поиска типа, чтобы если не нашли метод в текущем типе, то посмотреть в Any
fun Resolver.resolve(
    statements: List<Statement>,
    previousScope: MutableMap<String, Type>,
    rootStatement: Statement? = null, // since we in recursion, and can't define on what level of it, its the only way to know previous statement
): List<Statement> {
    val currentScope = mutableMapOf<String, Type>()

    statements.forEach { statement ->

        resolveStatement(
            statement,
            currentScope,
            previousScope,
            rootStatement
        )
    }

    topLevelStatements = topLevelStatements.filter {
        !(it is MessageSendKeyword && (it.receiver.str == "Project" || it.receiver.str == "Bind"))
    }.toMutableList()


    return statements
}

fun containSameFields(fields1: MutableList<TypeField>, fields2: MutableList<TypeField>): Boolean {
    if (fields1.count() != fields2.count()) return false

    fields1.forEachIndexed { i, t1 ->
        val t2 = fields2.find { compare2Types(t1.type, it.type) }
        if (t2 == null) return false
    }
    return true
}

fun Resolver.resolveDeclarations(
    statement: Declaration,
    previousScope: MutableMap<String, Type>,
    resolveBody: Boolean = true,
) {
    currentLevel += 1
    when (statement) {
        is TypeDeclaration -> {
            val newType = statement.toType(currentPackageName, typeTable, typeDB)// fixed
            addNewType(newType, statement)
        }

        is MessageDeclaration -> {
            if (resolveMessageDeclaration(statement, resolveBody, previousScope)) return
        }

        is UnionDeclaration -> {
            val rootType =
                statement.toType(currentPackageName, typeTable, typeDB, isUnion = true) as Type.UserUnionRootType//fix
            addNewType(rootType, statement)


            val branches = mutableListOf<Type.UserUnionBranchType>()
            val genericsOfBranches = mutableSetOf<Type>()
            statement.branches.forEach {
                val branchType =
                    it.toType(currentPackageName, typeTable, typeDB, root = rootType) as Type.UserUnionBranchType//fix
                branchType.parent = rootType


                // check if type already exist, and it doesn't have fields,
                // than it's not a new type, but branch with branches
                val tok = it.token
                val alreadyRegisteredType = typeAlreadyRegisteredInCurrentPkg(branchType, getCurrentPackage(tok), tok)
                if (alreadyRegisteredType == null) {
                    addNewType(branchType, it, checkedOnUniq = true)
                } else {

                    // check that it has the same fields as current root
                    if (!containSameFields(alreadyRegisteredType.fields, rootType.fields)) {
                        it.token.compileError("Union Root inside other union declaration must have same fields, ${branchType.name}: ${branchType.fields.map { it.name }} != ${rootType.name}: ${rootType.fields.map { it.name }}")
                    }
                    it.isRoot = true
                }

                branchType.fields += rootType.fields
                branches.add(branchType)
                genericsOfBranches.addAll(branchType.typeArgumentList)
            }
            rootType.branches = branches
            rootType.typeArgumentList = rootType.typeArgumentList + genericsOfBranches


            /// generics
            // add generics from branches
            val allGenerics = statement.collectAllGenericsFromBranches() + statement.genericFields
            statement.genericFields.clear()
            statement.genericFields.addAll(allGenerics)
            // not only to statement, but to Type too
        }

        is AliasDeclaration -> TODO()


        is UnionBranch -> {
            println("Union branch???")
        }
    }
    currentLevel -= 1
}


fun Resolver.resolveProjectKeyMessage(statement: MessageSend) {
    // add to the current project
    assert(statement.messages.count() == 1)
    val keyword = statement.messages[0] as KeywordMsg

    keyword.args.forEach {

        when (it.keywordArg) {
            is LiteralExpression.StringExpr -> {
                val substring = it.keywordArg.token.lexeme.removeDoubleQuotes()
                when (it.name) {
                    "name" -> changeProject(substring, statement.token)
                    "package" -> changePackage(substring, statement.token)
                    "protocol" -> changeProtocol(substring)
                    "use" -> usePackage(substring)
                    "import" -> usePackage(substring, true)
                    "target" -> changeTarget(substring, statement.token)
                    "mode" -> changeCompilationMode(substring, statement.token)
                    else -> statement.token.compileError("Unexpected argument ${it.name} for Project")
                }
            }

            is ListCollection -> {
                when (it.name) {
                    "loadPackages" -> {
                        if (it.keywordArg.initElements[0] !is LiteralExpression.StringExpr) {
                            it.keywordArg.token.compileError("packages must be listed as String")
                        }

                        generator.addToGradleDependencies(it.keywordArg.initElements.map { it.token.lexeme })
                    }
                }
            }

            else -> it.keywordArg.token.compileError("Only String args allowed for ${it.name}")
        }
    }
}

fun createEmptyKwConstructor(value: IdentifierExpr, valueType: Type, token: Token) = KeywordMsg(
    receiver = value, //IdentifierExpr("", token = createFakeToken()),
    selectorName = value.str,
    type = valueType,
    token = token,
    args = listOf(),
    path = listOf(),
    kind = KeywordLikeType.Constructor
)

private fun Resolver.resolveStatement(
    statement: Statement,
    currentScope: MutableMap<String, Type>,
    previousScope: MutableMap<String, Type>,
    rootStatement: Statement?
) {
    val resolveTypeForMessageSend = { statement2: MessageSend ->
        when (statement2.receiver.str) {
            "Project", "Bind" -> {}

            else -> {
                val previousAndCurrentScope = (previousScope + currentScope).toMutableMap()
                this.resolve(statement2.messages, previousAndCurrentScope, statement2)

                // TODO check then return parameter of each send match the next input parameter
                if (statement2.messages.isNotEmpty()) {
                    statement2.type =
                        statement2.messages.last().type
                            ?: statement2.token.compileError("Not all messages of ${statement2.str} has types")
                } else {
                    // every single expressions is unary message without messages
                    if (statement2.type == null) {
                        currentLevel++
                        resolve(listOf(statement2.receiver), previousAndCurrentScope, statement2)
                        currentLevel--
                    }
                    statement2.type = statement2.receiver.type
                        ?: statement2.token.compileError("Can't find type for ${statement2.str} on line ${statement2.token.line}")
                }
            }
        }

    }

    when (statement) {
        is Declaration -> {
            if (!allDeclarationResolvedAlready)
                resolveDeclarations(statement, previousScope, true)
            else if (statement is MessageDeclaration) {
                // first time all declarations resolved without bodyes, now we need to resolve them
                currentLevel++
                resolveMessageDeclaration(
                    statement,
                    true,
                    (currentScope + previousScope).toMutableMap(),
                    addToDb = false
                )
                currentLevel--
            }
        }

        is VarDeclaration -> {
            val previousAndCurrentScope = (previousScope + currentScope).toMutableMap()
            // currentNode, depth + 1
            currentLevel++
            resolve(listOf(statement.value), previousAndCurrentScope, statement)
            currentLevel--
            val value = statement.value
            val valueType = value.type
                ?: statement.token.compileError("In var declaration ${statement.name} value doesn't got type")
            val statementDeclaredType = statement.valueType

            // generics in right part, but real type in left, x::List::Int = List
            var copyType: Type? = null
            if (value is Receiver &&
                valueType is Type.UserType &&
                valueType.typeArgumentList.find { it.name.length == 1 && it.name[0].isUpperCase() } != null &&
                statementDeclaredType is TypeAST.UserType && statementDeclaredType.typeArgumentList.find { it.name.length == 1 && it.name[0].isUpperCase() } == null
            ) {
                copyType = valueType.copy()

                if (statementDeclaredType.typeArgumentList.count() != copyType.typeArgumentList.count()) {
                    statement.token.compileError(
                        "Not all generic type are presented, statement has ${
                            statementDeclaredType.typeArgumentList.joinToString(
                                ", "
                            ) { it.name }
                        } but type has ${copyType.typeArgumentList.joinToString(", ") { it.name }}"
                    )
                }
                val newTypeArgList: MutableList<Type> = mutableListOf()
                statementDeclaredType.typeArgumentList.forEachIndexed { i, typeAST ->
                    // find every type of statement and replace it in type

                    val e = typeDB.getTypeOfIdentifierReceiver(
                        typeAST.name,
                        value,
                        getCurrentImports(statement.token),
                        currentPackageName
                    ) ?: typeAST.token.compileError("Cant find type ${typeAST.name}")

                    e.beforeGenericResolvedName = copyType.typeArgumentList[i].name
                    newTypeArgList.add(e)
                }
                copyType.typeArgumentList = newTypeArgList
                value.type = copyType
            }
            //

            // if this is Type with zero fields constructor
            // replace Identifier with Keyword kind: Constructor
//            if (value.str[0].isUpperCase() && value is IdentifierExpr && valueType is Type.UserLike && valueType.fields.isEmpty()) {
//                statement.value = createEmptyKwConstructor(value, valueType, statement.token)
//            }


            // check that declared type == inferred type
            if (statementDeclaredType != null) {
                if (statementDeclaredType.name != valueType.name) {
                    val text = "${statementDeclaredType.name} != ${valueType.name}"

                    statement.token.compileError("Type declared for ${statement.name} is not equal for it's value type `$text`")
                }
            }

            currentScope[statement.name] = copyType ?: valueType

            if (currentLevel == 0) {
                topLevelStatements.add(statement)
            }

        }

        is Message -> {
            resolveMessage(statement, previousScope, currentScope)
        }

        is MessageSend -> {
            resolveTypeForMessageSend(statement)
            if (currentLevel == 0)
                topLevelStatements.add(statement)
        }


        is IdentifierExpr -> {
            val kw = if (rootStatement is KeywordMsg) {
                rootStatement
            } else null

            getTypeForIdentifier(
                statement, previousScope, currentScope, kw
            )
            val type = statement.type
            if (type is Type.UserLike && statement.str == type.name) {
                if (type.fields.isEmpty()) {
                    statement.isConstructor = true
                } else if (kw == null ) {

                    // this can be a custom constructor like Person sas
                    // so there is fields, but not kw message of that fields(constructor)
//                    statement.token.compileError("You forget to add params to construct type `${statement.name}` expected: ${type.fields} ")
                }
            }

            if (type is Type.UserLike && type.fields.isEmpty() && statement.str == type.name) {
                statement.isConstructor = true
            }

            if (currentLevel == 0) topLevelStatements.add(statement)
        }

        is ExpressionInBrackets -> {
            resolveExpressionInBrackets(statement, previousScope, currentScope)
            if (currentLevel == 0) topLevelStatements.add(statement)
        }

        is CodeBlock -> {
            resolveCodeBlock(statement, previousScope, currentScope, rootStatement)
        }

        is ListCollection -> {

            if (statement.initElements.isNotEmpty()) {
                val firstElem = statement.initElements[0]
                if (firstElem.typeAST != null) {
                    val firstElemType = firstElem.typeAST.toType(typeDB, typeTable)//fix
                    firstElemType.beforeGenericResolvedName = "T" // Default List has T type
                    val listType =
                        this.projects["common"]!!.packages["core"]!!.types["MutableList"] as Type.UserType

                    // try to find list with the same generic type
                    val typeName = "MutableList"
                    val currentPkg = getCurrentPackage(statement.token)

                    val listProtocols = listType.protocols

                    val genericType = Type.UserType( // alreadyExistsListType ?:
                        name = typeName,
                        typeArgumentList = listOf(firstElemType),
                        fields = mutableListOf(),
                        pkg = currentPackageName,
                        protocols = listProtocols
                    )

                    statement.type = genericType
                } else {
                    statement.token.compileError("Cant get type of elements of list literal")
                }
            } else if (rootStatement is VarDeclaration && rootStatement.valueType != null) {
                val type = rootStatement.valueType!!.toType(typeDB, typeTable)//fix
                statement.type = type
            }

            if (currentLevel == 0) {
                topLevelStatements.add(statement)
            }
        }

        is SetCollection -> {

            if (statement.initElements.isNotEmpty()) {
                val firstElem = statement.initElements[0]
                if (firstElem.typeAST != null) {
                    val firstElemType = firstElem.typeAST.toType(typeDB, typeTable)//fix
                    firstElemType.beforeGenericResolvedName = "T" // Default List has T type
                    val listType =
                        this.projects["common"]!!.packages["core"]!!.types["MutableSet"] as Type.UserType

                    // try to find list with the same generic type
                    val typeName = "MutableSet"

                    val listProtocols = listType.protocols

                    val genericType = Type.UserType( // alreadyExistsListType ?:
                        name = typeName,
                        typeArgumentList = listOf(firstElemType),
                        fields = mutableListOf(),
                        pkg = currentPackageName,
                        protocols = listProtocols
                    )

                    statement.type = genericType
                } else {
                    statement.token.compileError("Cant get type of elements of list literal")
                }
            } else if (rootStatement is VarDeclaration && rootStatement.valueType != null) {
                val type = rootStatement.valueType!!.toType(typeDB, typeTable)//fix
                statement.type = type
            }

            if (currentLevel == 0) {
                topLevelStatements.add(statement)
            }
        }

        is MapCollection -> {
            // get type of the first key
            // get type of the first value
            if (statement.initElements.isEmpty() && (rootStatement is VarDeclaration && rootStatement.valueType != null)) {
                val type = rootStatement.valueType!!.toType(typeDB, typeTable)//fix
                statement.type = type
                return
            }
            val (key, value) = statement.initElements[0]
            currentLevel++
            resolve(listOf(key), (currentScope + previousScope).toMutableMap(), statement)
            resolve(listOf(value), (currentScope + previousScope).toMutableMap(), statement)
            currentLevel--

            val keyType = key.type ?: key.token.compileError("Can't resolve type of key: ${key.str}")
            val valueType = value.type ?: value.token.compileError("Can't resolve type of value: ${value.str}")

            val mapTypeFromDb =
                this.projects["common"]!!.packages["core"]!!.types["MutableMap"] as Type.UserType
            val listTypeFromDb =
                this.projects["common"]!!.packages["core"]!!.types["MutableList"] as Type.UserType

            val listTypeOfValues = Type.UserType(
                name = "MutableList",
                typeArgumentList = listOf(valueType),
                fields = mutableListOf(),
                pkg = currentPackageName,
                protocols = listTypeFromDb.protocols
            )
            val listTypeOfKeys = Type.UserType(
                name = "MutableList",
                typeArgumentList = listOf(keyType),
                fields = mutableListOf(),
                pkg = currentPackageName,
                protocols = listTypeFromDb.protocols
            )

            val mapType = Type.UserType(
                name = "MutableMap",
                typeArgumentList = listOf(keyType, valueType),
                fields = mutableListOf(
                    TypeField("values", listTypeOfValues),
                    TypeField("keys", listTypeOfKeys)
                ),
                pkg = currentPackageName,
                protocols = mapTypeFromDb.protocols
            )
            statement.type = mapType

            if (currentLevel == 0) {
                topLevelStatements.add(statement)
            }

        }

        is LiteralExpression.FloatExpr ->
            statement.type = Resolver.defaultTypes[InternalTypes.Float]

        is LiteralExpression.IntExpr ->
            statement.type = Resolver.defaultTypes[InternalTypes.Int]

        is LiteralExpression.StringExpr ->
            statement.type = Resolver.defaultTypes[InternalTypes.String]

        is LiteralExpression.CharExpr ->
            statement.type = Resolver.defaultTypes[InternalTypes.Char]

        is LiteralExpression.TrueExpr ->
            statement.type = Resolver.defaultTypes[InternalTypes.Boolean]

        is LiteralExpression.FalseExpr ->
            statement.type = Resolver.defaultTypes[InternalTypes.Boolean]

        is TypeAST.InternalType -> {}
        is TypeAST.Lambda -> {}
        is TypeAST.UserType -> {}

        is ControlFlow -> {
            resolveControlFlow(statement, previousScope, currentScope, rootStatement)
        }

        is Assign -> {

            val previousAndCurrentScope = (previousScope + currentScope).toMutableMap()
            resolve(listOf(statement.value), previousAndCurrentScope, statement)



            if (currentLevel == 0) {
                topLevelStatements.add(statement)
            }
        }

        is ReturnStatement -> {
            val previousAndCurrentScope = (previousScope + currentScope).toMutableMap()
            val expr = statement.expression
            if (expr != null) {
                resolve(listOf(expr), previousAndCurrentScope, statement)
                if (expr.type == null) {
                    throw Exception("Cant infer type of return statement on line: ${expr.token.line}")
                }
            }
            val unit = Resolver.defaultTypes[InternalTypes.Unit]!!
            val q = if (expr == null) unit else expr.type!!
            wasThereReturn = q
            if (rootStatement is MessageDeclaration) {
                val w = rootStatement.returnTypeAST?.toType(typeDB, typeTable)//fix
                if (w != null) {
                    val isReturnTypeEqualToReturnExprType = compare2Types(q, w)
                    if (!isReturnTypeEqualToReturnExprType) {
                        statement.token.compileError("return type is `${w.name}` but found `${q.name}`")
                    }
                }
            }
        }

        is DotReceiver -> {
            statement.type = findThis(statement.token, currentScope, previousScope)
        }

    }
}

fun Resolver.resolveExpressionInBrackets(
    statement: ExpressionInBrackets,
    currentScope: MutableMap<String, Type>,
    previousScope: MutableMap<String, Type>
): Type {
    if (statement.statements.isEmpty()) {
        statement.token.compileError("Parens must contain expression")
    }
    val lastExpr = statement.statements.last()
    if (lastExpr !is Expression) {
        statement.token.compileError("Last statement inside parens must be expression")
    }

    val previousAndCurrentScope = (previousScope + currentScope).toMutableMap()
    resolve(statement.statements, previousAndCurrentScope, statement)
    statement.type = lastExpr.type
    return lastExpr.type!!
}

fun compareAstWithType(astType: TypeAST, type: Type): Boolean {
    return astType.name == type.name
}

fun compare2Types(type1: Type, type2: Type, token: Token? = null): Boolean {
    // one of the types is Any
    if (type1.name == InternalTypes.Any.name || type2.name == InternalTypes.Any.name) {
        return true
    }

    if (type1 is Type.Lambda && type2 is Type.Lambda) {
        if (type1.args.count() != type2.args.count()) {
            token?.compileError("Codeblock `${type1.name}` has ${type1.args.count()} arguments but `${type2.name}` has ${type2.args.count()}")
            return false
        }

        type1.args.forEachIndexed { i, it ->
            val it2 = type2.args[i]
            val isEqual = compare2Types(it.type, it2.type)
            if (!isEqual) {
                token?.compileError("argument ${it.name} has type ${it.type} but ${it2.name} has type ${it2.type}")
                return false
            }
        }

        // check return types
        val return1 = type1.returnType
        val return2 = type2.returnType
        val isReturnTypesEqual = (return2.name == InternalTypes.Unit.name || return1.name == InternalTypes.Unit.name) || compare2Types(return1, return2)
        if (!isReturnTypesEqual) {
            token?.compileError("return types are not equal: $type1 != $type2")
        }

        return true
    }

    // TODO temp, there could be types with same names in different packages
    if (type1.name == type2.name) {
        return true
    }

    if (type1 is Type.UserLike && type2 is Type.UserLike) {
        // first is parent of the second
        var parent1: Type? = type1.parent
        while (parent1 != null) {
            if (compare2Types(type2, parent1)) {
                return true
            }
            parent1 = parent1.parent
        }
        // second is parent of the first
        var parent2: Type? = type2.parent
        while (parent2 != null) {
            if (compare2Types(type1, parent2)) {
                return true
            }
            parent2 = parent2.parent
        }
    }

    if (type1 is Type.UnknownGenericType || type2 is Type.UnknownGenericType) {
        return true
    }


    // comparing with nothing is always true, its bottom type, subtype of all types
    // so we can return nothing from switch expr branches, beside u cant do it with different types
    val nothing = Resolver.defaultTypes[InternalTypes.Nothing]
    return type1 == nothing || type2 == nothing
}

fun Package.addImport(pkg: String, concrete: Boolean = false) {
    if (packageName != pkg) {
        if (!concrete) {
            imports.add(pkg)
        } else {
            concreteImports.add(pkg)
        }
    }

}

//fun Resolver.findAnyMessage(receiverType: Type)

fun Resolver.findUnaryMessageType(receiverType: Type, selectorName: String, token: Token): UnaryMsgMetaData {

    fun findUnary(receiverType: Type, selectorName: String, token: Token): UnaryMsgMetaData? {
        receiverType.protocols.forEach { (_, v) ->
            val q = v.unaryMsgs[selectorName]

            if (q != null) {
                val pkg = getCurrentPackage(token)
                // method can be declared in different package than it's receiver type
                pkg.addImport(q.pkg)
                return q
            }
        }
        return null
    }

    val result = findUnary(receiverType, selectorName, token)
    if (result != null)
        return result

    var parent: Type? = receiverType.parent
    while (parent != null) {
        val parentResult = findUnary(parent, selectorName, token)
        if (parentResult != null)
            return parentResult
        parent = parent.parent
    }

    // this is Any
    val anyType = Resolver.defaultTypes[InternalTypes.Any]!!
    val messageFromAny = findUnary(anyType, selectorName, token)
    if (messageFromAny != null) {
        return messageFromAny
    }

    token.compileError("Cant find unary message: $selectorName for type ${receiverType.pkg}.${receiverType.name}")
}


// returns true if it is static call, but not constructor(so we generate Clock.System instead of Clock.System())
fun Resolver.findStaticMessageType(
    receiverType: Type,
    selectorName: String,
    token: Token,
    msgType: MessageDeclarationType? = null
): Pair<MessageMetadata, Boolean> {
    receiverType.protocols.forEach { (_, v) ->
        val q = v.staticMsgs[selectorName]
        if (q != null) {
            val pkg = getCurrentPackage(token)
            pkg.addImport(receiverType.pkg)
            return Pair(q, false)
        }
    }

    // if this is binding, then getters are static, calls without ()
    if (msgType != null && getPackage(receiverType.pkg, token).isBinding) {
        when (msgType) {
            MessageDeclarationType.Unary ->
                return Pair(findUnaryMessageType(receiverType, selectorName, token), true)

            MessageDeclarationType.Keyword ->
                return Pair(findKeywordMsgType(receiverType, selectorName, token), true)

            MessageDeclarationType.Binary -> TODO()
        }

    }

    throw Exception("Cant find static message: $selectorName for type ${receiverType.name}")
//    token.compileError("Cant find static message: $selectorName for type ${receiverType.name}")
}

fun Resolver.findBinaryMessageType(receiverType: Type, selectorName: String, token: Token): BinaryMsgMetaData {
    if (receiverType.name.length == 1 && receiverType.name[0].isUpperCase()) {
        throw Exception("Can't receive generic type to find binary method for it")
    }
    receiverType.protocols.forEach { (_, v) ->
        val q = v.binaryMsgs[selectorName]
        if (q != null) {
            val pkg = getCurrentPackage(token)
            pkg.addImport(q.pkg)
            return q
        }
    }
    token.compileError("Cant find binary message: $selectorName for type ${receiverType.name}")
}

fun Resolver.findKeywordMsgType(receiverType: Type, selectorName: String, token: Token): KeywordMsgMetaData {
    if (receiverType.name.length == 1 && receiverType.name[0].isUpperCase()) {
        throw Exception("Can't receive generic type to find keyword method for it")
    }

    receiverType.protocols.forEach { (_, v) ->
        val q = v.keywordMsgs[selectorName]
        if (q != null) {
            // TODO! add using of keyword to msgs list of method, maybe
            val pkg = getCurrentPackage(token)
            pkg.addImport(q.pkg)
            return q
        }
    }
    token.compileError("Cant find keyword message: $selectorName for type ${receiverType.name}")
}


fun Resolver.getPackage(packageName: String, token: Token): Package {
    val p = this.projects[currentProjectName] ?: token.compileError("there are no such project: $currentProjectName")
    val pack = p.packages[packageName] ?: token.compileError("there are no such package: $packageName")
    val q = "asdasd"
    q.substring(1, 2)
    return pack
}


fun Resolver.getCurrentProtocol(typeName: String, token: Token, customPkg: Package? = null): Pair<Protocol, Package> {
    val pack = customPkg ?: getPackage(currentPackageName, token)
    val type = pack.types[typeName]
        ?: getPackage("common", token).types[typeName]
        ?: getPackage("core", token).types[typeName]
        ?: token.compileError("there are no such type: $typeName in package $currentPackageName in project: $currentProjectName")

    val protocol = type.protocols[currentProtocolName]

    if (protocol == null) {
        val newProtocol = Protocol(currentProtocolName)
        type.protocols[currentProtocolName] = newProtocol
        return Pair(newProtocol, pack)
    }
    return Pair(protocol, pack)
}

fun Resolver.getCurrentPackage(token: Token) = getPackage(currentPackageName, token)
fun Resolver.getCurrentImports(token: Token) = getCurrentPackage(token).imports

fun Resolver.addStaticDeclaration(statement: ConstructorDeclaration): MessageMetadata {
    val typeOfReceiver = typeTable[statement.forTypeAst.name]!!//testing
//    val testDB = typeDB.getType(statement.forTypeAst.name)

    val messageData = when (statement.msgDeclaration) {
        is MessageDeclarationUnary -> {
//            staticUnaryForType[statement.name] = statement.msgDeclaration
            val (protocol, pkg) = getCurrentProtocol(statement.forTypeAst.name, statement.token)

            // if return type is not declared then use receiver
            val returnType = if (statement.returnTypeAST == null)
                typeOfReceiver
            else
                statement.returnType ?: statement.returnTypeAST.toType(typeDB, typeTable)

            val messageData = UnaryMsgMetaData(
                name = statement.msgDeclaration.name,
                returnType = returnType,
                codeAttributes = statement.pragmas,
                pkg = pkg.packageName
            )

            protocol.staticMsgs[statement.name] = messageData
            messageData
        }

        is MessageDeclarationBinary -> {
            statement.token.compileError("Binary static message, really? This is not allowed")
        }

        is MessageDeclarationKeyword -> {
//            staticKeywordForType[statement.name] = statement.msgDeclaration
            val (protocol, pkg) = getCurrentProtocol(statement.forTypeAst.name, statement.token)

            val keywordArgs = statement.msgDeclaration.args.map {
                KeywordArg(
                    name = it.name,
                    type = it.type?.toType(typeDB, typeTable)//fix
                        ?: statement.token.compileError("Type of keyword message ${statement.msgDeclaration.name}'s arg ${it.name} not registered")
                )
            }
            val messageData = KeywordMsgMetaData(
                name = statement.msgDeclaration.name,
                argTypes = keywordArgs,
                returnType = typeOfReceiver,
                codeAttributes = statement.pragmas,
                pkg = pkg.packageName
            )
            protocol.staticMsgs[statement.name] = messageData
            messageData
        }

        is ConstructorDeclaration -> TODO()
    }
    addMsgToPackageDeclarations(statement)
    return messageData
}

fun Resolver.addNewUnaryMessage(statement: MessageDeclarationUnary, isGetter: Boolean = false): MessageMetadata {
    val customPkg = if (statement.forTypeAst is TypeAST.UserType && statement.forTypeAst.names.count() > 1) {
        getPackage(statement.forTypeAst.names.dropLast(1).joinToString("."), statement.token)
    } else null
    val (protocol, pkg) = getCurrentProtocol(statement.forTypeAst.name, statement.token, customPkg)

    val messageData = statement.toMessageData(typeDB, typeTable, pkg, isGetter)//fix
    protocol.unaryMsgs[statement.name] = messageData

    addMsgToPackageDeclarations(statement)
    return messageData
}

fun Resolver.addNewBinaryMessage(statement: MessageDeclarationBinary): MessageMetadata {
    val (protocol, pkg) = getCurrentProtocol(statement.forTypeAst.name, statement.token)
    val messageData = statement.toMessageData(typeDB, typeTable, pkg)//fix
    protocol.binaryMsgs[statement.name] = messageData

    addMsgToPackageDeclarations(statement)
    return messageData
}

fun Resolver.addNewKeywordMessage(statement: MessageDeclarationKeyword): MessageMetadata {
    val (protocol, pkg) = getCurrentProtocol(statement.forTypeAst.name, statement.token)
    val messageData = statement.toMessageData(typeDB, typeTable, pkg)//fix
    protocol.keywordMsgs[statement.name] = messageData

    // add msg to package declarations
    addMsgToPackageDeclarations(statement)
    return messageData
}

fun Resolver.addMsgToPackageDeclarations(statement: Declaration) {
    val pack = getPackage(currentPackageName, statement.token)
    pack.declarations.add(statement)
}

private class FakeToken {
    companion object {
        val fakeToken = Token(
            TokenType.Identifier, "Fake Token", 0, Position(0, 1),
            Position(0, 1), File("Nothing")
        )
    }
}

fun createFakeToken(): Token = FakeToken.fakeToken

fun Resolver.typeAlreadyRegisteredInCurrentPkg(type: Type, pkg: Package? = null, token: Token? = null): Type.UserLike? {
    val pack = pkg ?: getCurrentPackage(token ?: createFakeToken())
    val result = pack.types[type.name]
    return result as? Type.UserLike
}

fun Resolver.addNewType(
    type: Type,
    statement: SomeTypeDeclaration?,
    pkg: Package? = null,
    checkedOnUniq: Boolean = false
) {
    val pack = pkg ?: getCurrentPackage(
        statement?.token ?: createFakeToken()
    ) // getPackage(currentPackageName, statement?.token ?: createFakeToken())

    if (!checkedOnUniq && typeAlreadyRegisteredInCurrentPkg(type, pkg, statement?.token) != null) {
        val err = "Type ${type.name} already registered in project: $currentProjectName in package: $currentPackageName"
        statement?.token?.compileError(err) ?: throw Exception(err)
    }

    if (statement != null) {
        pack.declarations.add(statement)
    }

    if (pack.isBinding && type is Type.UserLike) {
        type.isBinding = true
    }
    pack.types[type.name] = type
    typeTable[type.name] = type//fix
    typeDB.add(type, token = statement?.token ?: createFakeToken())
}


fun Resolver.changeProject(newCurrentProject: String, token: Token) {
    // clear all current, load project
    currentProjectName = newCurrentProject
    // check that there are no such project already

    if (projects[newCurrentProject] != null) {
        token.compileError("Project with name: $newCurrentProject already exists")
    }
    val commonProject = projects["common"] ?: token.compileError("Can't find common project")


    projects[newCurrentProject] = Project(
        name = newCurrentProject,
        usingProjects = mutableListOf(commonProject)
    )

    TODO()
}

fun Resolver.changePackage(
    newCurrentPackage: String,
    token: Token,
    isBinding: Boolean = false,
    isMainFile: Boolean = false
) {
    currentPackageName = newCurrentPackage
    val currentProject = projects[currentProjectName] ?: token.compileError("Can't find project: $currentProjectName")
    val alreadyExistsPack = currentProject.packages[newCurrentPackage]

    // check that this package not exits already
    if (alreadyExistsPack != null) {
        // load table of types
        typeTable.putAll(alreadyExistsPack.types)//fixed
        alreadyExistsPack.types.values.forEach {
            if (it is Type.InternalType) {
                typeDB.addInternalType(it.name, it)
            }
            if (it is Type.UserLike) {
                typeDB.addUserLike(it.name, it, token)
            }
        }

    } else {
        // create this new package
        val pack = Package(
            packageName = newCurrentPackage,
            isBinding = isBinding
        )

        currentProject.packages[newCurrentPackage] = pack
        // top level statements and default defenitions located in different pkgs
        // so to add access from top level statements(mainNiva) to this defenitions
        // we need to always import it
        if (isMainFile) {
            val mainNivaPkg = currentProject.packages[MAIN_PKG_NAME]!!
            mainNivaPkg.addImport(newCurrentPackage)
        }
    }

}

fun Resolver.changeProtocol(protocolName: String) {
    currentProtocolName = protocolName
}

fun Resolver.usePackage(packageName: String, noStarImport: Boolean = false) {
    val currentPkg = getCurrentPackage(this.statements.last().token)
    currentPkg.addImport(packageName, noStarImport)
}

enum class CompilationTarget {
    jvm,
    linux,
    macos,
//    windows,
}

enum class CompilationMode {
    release,
    debug,
}


fun Resolver.changeTarget(target: String, token: Token) {
    fun targetFromString(target: String, token: Token): CompilationTarget = when (target) {
        "jvm" -> CompilationTarget.jvm
        "linux" -> CompilationTarget.linux
        "macos" -> CompilationTarget.macos
        "windows" -> token.compileError("Windows native target not supported yet")
        "js" -> token.compileError("js target not supported yet")
        else -> token.compileError("There is no such target as $target, supported targets are ${CompilationTarget.entries.map { it.name }}, default: jvm")
    }

    val target = targetFromString(target, token)
    compilationTarget = target
}

fun CompilationMode.toRunTask() = when (this) {
    CompilationMode.release -> "runLinuxX64ReleaseExecutableLinuxX64"
    CompilationMode.debug -> "runLinuxX64DebugExecutableLinuxX64"
}

fun CompilationMode.toCompileOnlyTask(target: CompilationTarget): String {
    if (target == CompilationTarget.jvm) return "dist"
    val targetStr = when (target) {
        CompilationTarget.linux -> "LinuxX64"
        CompilationTarget.macos -> "MacosArm64"
        else -> TODO()
    }
    val compMode = when (this) {
        CompilationMode.release -> "Release"
        CompilationMode.debug -> "Debug"
    }

    return "link$targetStr${compMode}Executable$targetStr"
}

fun CompilationMode.toBinaryPath(target: CompilationTarget, pathToProjectRoot: String): String {
    if (target == CompilationTarget.jvm) return ""
    val targetStr = when (target) {
        CompilationTarget.linux -> "linuxX64"
        CompilationTarget.macos -> "macosArm64"
        else -> TODO()
    }
    val compMode = when (this) {
        CompilationMode.release -> "${targetStr}ReleaseExecutable"
        CompilationMode.debug -> "${targetStr}DebugExecutable"
    }

    return pathToProjectRoot / "build" / "bin" / targetStr / compMode / "kotlin.kexe"
}


fun Resolver.changeCompilationMode(mode: String, token: Token) {
    fun modeFromString(mode: String, token: Token): CompilationMode = when (mode) {
        "release" -> CompilationMode.release
        "debug" -> CompilationMode.debug
        else -> token.compileError("There is no such compilation mode as $mode, supported targets are ${CompilationMode.entries.map { it.name }}, default: debug")
    }

    val modeEnum = modeFromString(mode, token)
    compilationMode = modeEnum
}


fun Resolver.getTypeForIdentifier(
    x: IdentifierExpr,
    currentScope: MutableMap<String, Type>,
    previousScope: MutableMap<String, Type>,
    kw: KeywordMsg? = null
): Type? {
    val type = if (kw != null) getType2(x.name, currentScope, previousScope, kw) else getType(
        x.name,
        currentScope,
        previousScope
    )
        ?: x.token.compileError("Unresolved reference: ${x.str}")

    x.type = type
    return type
}


fun Resolver.getType2(
    typeName: String,
    currentScope: MutableMap<String, Type>,
    previousScope: MutableMap<String, Type>,
    statement: KeywordMsg
): Type? {
    val q = typeDB.getType(typeName, currentScope, previousScope)
    val currentPackage = getCurrentPackage(statement.token)

    val type = q.getTypeFromTypeDBResultConstructor(statement, currentPackage.imports, currentPackageName)
    return type
}

fun Resolver.getType(
    typeName: String,
    currentScope: MutableMap<String, Type>,
    previousScope: MutableMap<String, Type>
): Type? {
    return typeTable[typeName]//get
        ?: currentScope[typeName]
        ?: previousScope[typeName]
        ?: findTypeInAllPackages(typeName)
}


inline fun Resolver.getCurrentProject(): Project {
    return projects[currentProjectName]!!
}

fun Project.findTypeInAllPackages(x: String): Type? {
    val packages = this.packages.values
    packages.forEach {
        val result = it.types[x]
        if (result != null) {
            return result
        }
    }

    return null
}

fun Resolver.findTypeInAllPackages(x: String): Type? {
    val packages = projects[currentProjectName]!!.packages.values
    packages.forEach {
        val result = it.types[x]
        if (result != null) {
            return result
        }
    }

    return null
}


fun IfBranch.getReturnTypeOrThrow(): Type = when (this) {
    is IfBranch.IfBranchSingleExpr -> {
        this.thenDoExpression.type!!
    }

    is IfBranch.IfBranchWithBody -> {
        if (body.isEmpty())
            Resolver.defaultTypes[InternalTypes.Unit]!!
        else
            when (val last = body.last()) {
                is Expression -> last.type!!
    //            is ReturnStatement -> last.expression.type!!
                else -> Resolver.defaultTypes[InternalTypes.Unit]!!
            }
    }
}
