package frontend.parser.parsing

import frontend.meta.TokenType
import frontend.meta.compileError
import frontend.parser.types.ast.*
import frontend.util.capitalizeFirstLetter
import main.RED
import main.WHITE

// also recevier can be unary or binary message

// receiver examples - (receiver)
// (1) sas // simple unary
// (1) + 2 // simple binary
// (1) to: 2 // simple keyword
// (1 sas) to: 2 // keyword with unary receiver
// (1 + 2) to: 2 // keyword with binary receiver
// (1 inc + 2 inc) to: 2 // keyword with binary receiver
// So unary and binary messages can be the receiver,
// also collections
// also code blocks


fun Parser.unaryOrBinaryMessageOrPrimaryReceiver(customReceiver: Receiver? = null, insideKeywordArgument: Boolean = false): Receiver {

    val safePoint = current
    try {
        // we don't need to parse cascade if we are inside keyword argument parsing, since this cascade will be applied to
        // the kw argument itself, like x from: 1 - 1 |> echo, echo will be applied to 1 - 1, not x
        // or Person name: "Alice" |> getName
        when (val messageSend = unaryOrBinary(customReceiver = customReceiver, parsePipeAndCascade = !insideKeywordArgument)) {
            is MessageSendUnary, is MessageSendBinary -> {
                return if (messageSend.messages.isNotEmpty()) {
                    messageSend
                } else
                    messageSend.receiver
            }

            is MessageSendKeyword -> error("keyword cant be a receiver, for now")
        }
    } catch (e: Throwable) {
        if (e.message?.startsWith("Error") == true) {
            throw e
        }
        current = safePoint
    }
    current = safePoint
    // no messages as receiver
    return simpleReceiver()
}


// receiver like collection, code block, identifier,

// simple means not message
fun Parser.simpleReceiver(typeAst: TypeAST? = null): Receiver {

    if (check(TokenType.OpenBracket)) {
        return codeBlock()
    }
    if (check(TokenType.OpenParen)) {
        return bracketExpression()
    }

    val readPrimaryCollection = {
        val initElements = mutableListOf<Primary>()

        var lastPrimary: Primary? = null
        do {
            val primaryTok = primary(typeAst)
            match(TokenType.Comma)
            if (primaryTok != null) {
                if (lastPrimary != null && primaryTok.type?.name != lastPrimary.type?.name) {
                    error("Heterogeneous collections are not supported")
                }
                initElements.add(primaryTok)
            }
            lastPrimary = primaryTok
        } while (primaryTok != null)

        initElements
    }

    val readPrimaryMap = {
        val initElementsPairs: MutableList<Pair<Receiver, Receiver>> = mutableListOf()
        do {
            val primaryTok = primary(typeAst)
            if (match(TokenType.Comma)) {
                peek().compileError("Only map pairs can be separated by commas")
            }

            val primaryTok2 = primary(typeAst)
            match(TokenType.Comma)
            skipOneEndOfLineOrFile()

            if (primaryTok != null && primaryTok2 != null) {
                initElementsPairs.add(Pair(primaryTok, primaryTok2))
            }

            if (primaryTok != null && primaryTok2 == null) {
                peek().compileError("Map must contain even elements")
            }

        } while (primaryTok != null)

        initElementsPairs
    }

    var tryPrimary: Receiver? = primary(typeAst)
    // check for collections
    if (tryPrimary == null) {
        val q = step()
        skipOneEndOfLineOrFile()

        tryPrimary = when (q.kind) {
            TokenType.OpenBrace -> {
                // {1, 2 3}
                // for now, messages inside collection literals are impossible

                //if there are keyword call, then read collection of constructors
                val initElements = readPrimaryCollection()
                match(TokenType.CloseBrace)

                val type = if (initElements.isNotEmpty()) initElements[0].type else null
                return ListCollection(initElements, type, q)
            }

            TokenType.OpenBraceHash -> {
                // #{"a" 1 "b" 2}
                val initElements = readPrimaryMap()
                skipOneEndOfLineOrFile()

                match(TokenType.CloseBrace)

                return MapCollection(initElements, null, q)
            }

            TokenType.OpenParenHash -> {
                // #(1, 2 3)
                val initElements = readPrimaryCollection()
                skipOneEndOfLineOrFile()

                match(TokenType.CloseParen)

                val type = if (initElements.isNotEmpty()) initElements[0].type else null
                return SetCollection(initElements, type, q)
            }


            else -> null
        }

    }

    if (tryPrimary == null) {
        peek().compileError("Can't parse primary token, got ${WHITE}${peek().lexeme}")
    }
    return tryPrimary
}


fun Parser.returnType(): TypeAST? {
    if (!match(TokenType.ReturnArrow)) {
        return null
    }
    val returnType = parseType()
    return returnType
}

fun Parser.unaryDeclaration(forTypeAst: TypeAST): MessageDeclarationUnary {



    // int^ inc = []

    val unarySelector = matchAssertAnyIdent("Its unary message declaration, unary selector expected")

    val returnType = returnType()
    ///// BODY PARSING

    val isInline = match(TokenType.Return)

    val pair = methodBody() // (body, is single expression)
    val messagesOrVarDeclarations = pair.first
    val isSingleExpression = pair.second
    // end of body parsing

    if (!isSingleExpression) {
        match(TokenType.CloseBracket)
    }

    val result = MessageDeclarationUnary(
        name = unarySelector.lexeme,
        forType = forTypeAst,
        token = forTypeAst.token,
        body = messagesOrVarDeclarations,
        returnType = returnType,
        isSingleExpression = isSingleExpression,
        isInline = isInline
    )
    return result
}

fun Parser.binaryDeclaration(forType: TypeAST): MessageDeclarationBinary {
    // int^ + x = []

    val binarySelector = matchAssert(TokenType.BinarySymbol, "Its binary message declaration, binary selector expected")
    // int + ^x = []
    // int + ^x::int = []

    // arg

    val argName = matchAssertAnyIdent("in binary message identifier after operator expected")
    val typeName =
        if (match(TokenType.DoubleColon))
            parseType()
        else null
    val arg = (KeywordDeclarationArg(name = argName.lexeme, type = typeName))
    val returnType = returnType()

    // BODY PARSING
    val pair = methodBody() // (body, is single expression)
    val messagesOrVarDeclarations = pair.first
    val isSingleExpression = pair.second
    // end of body parsing

    if (!isSingleExpression) {
        match(TokenType.CloseBracket)
    }

    val result = MessageDeclarationBinary(
        name = binarySelector.lexeme,
        forType = forType,
        token = forType.token,
        arg = arg,
        body = messagesOrVarDeclarations,
        returnType = returnType,
        isSingleExpression = isSingleExpression
    )
    return result
}


/**
 * Parses a keyword message declaration, which follows the format:
 *  - Receiver type, followed by arguments.
 *  - Optional return type.
 *  - Body with messages or variable declarations.
 * The message name for the keyword message is produced by concatenating the argument names with capitalized first letters.
 * The function returns a [MessageDeclarationKeyword] object representing the parsed keyword message declaration.
 */
fun Parser.keywordDeclaration(forType: TypeAST): MessageDeclarationKeyword {
    val args = mutableListOf<KeywordDeclarationArg>()

    do {
        // it can be no type no local name :key
        // type, no local name key::int      key2::string
        // type and local name: to: x::int   from: y::int
        skipNewLinesAndComments()
        args.add(keyArg())
        skipNewLinesAndComments()

    } while (!(check(TokenType.Assign) || check(TokenType.ReturnArrow)))


    val returnType = returnType()

    // BODY PARSING
    val pair = methodBody()
    val messagesOrVarDeclarations = pair.first
    val isSingleExpression = pair.second
    // end of body parsing

    if (!isSingleExpression) {
        match(TokenType.CloseBracket)
    }

    val keywordMessageName = args[0].name + args.drop(1).map { it.name.capitalizeFirstLetter() }.joinToString("") { it }
    val result = MessageDeclarationKeyword(
        name = keywordMessageName,
        forType = forType,
        token = forType.token,
        args = args,
        body = messagesOrVarDeclarations,
        returnType = returnType,
        isSingleExpression = isSingleExpression
    )
    return result
}

// x::int or x: local::int or x: local or :x
private fun Parser.keyArg(): KeywordDeclarationArg {
    val noLocalNameNoType = check(TokenType.Colon)
    val noLocalName = check(TokenType.Identifier) && check(TokenType.DoubleColon, 1)
    // :foo
    if (noLocalNameNoType) {
        step() //skip colon
        val argName = step()
        if (argName.kind != TokenType.Identifier) {
            argName.compileError("You tried to declare keyword message with arg without type and local name, identifier expected after colon :foobar")
        }
        return (KeywordDeclarationArg(name = argName.lexeme))
    }
    // arg::int
    else if (noLocalName) {
        val argName = step()
        if (argName.kind != TokenType.Identifier) {
            error("You tried to declare keyword message with arg without local name, identifier expected before double colon foobar::type")
        }
        match(TokenType.DoubleColon)
        val type = parseType()
        return (KeywordDeclarationArg(name = argName.lexeme, type = type))
    }
    // key: localName(::int)?
    else {
        val key = matchAssert(TokenType.Identifier)
        match(TokenType.Colon)
        val local = step()
        val type: TypeAST? = if (check(TokenType.DoubleColon)) {
            step()// skip doubleColon
            parseType()
        } else {
            null
        }

        val result = KeywordDeclarationArg(name = key.lexeme, localName = local.lexeme, type = type)
        return result

    }
}


// returns true if it's single expression
fun Parser.methodBody(
    isControlFlow: Boolean = false,
): Pair<MutableList<Statement>, Boolean> {
    val isSingleExpression: Boolean
    val messagesOrVarStatements = mutableListOf<Statement>()
    // Person from: x ^= []
    val isThereAssignOrThen = match(TokenType.Assign) || isControlFlow
    if (!isThereAssignOrThen) {
        return Pair(mutableListOf(), false)
    }
    // many expressions in body
    if (match(TokenType.OpenBracket)) {
        isSingleExpression = false

        skipNewLinesAndComments()
        while (!match(TokenType.CloseBracket)) {
            messagesOrVarStatements.add(statementWithEndLine())
        }
    } else {
        isSingleExpression = true
        // one expression in body
        // if we inside control flow then dont skip lines, because
        // switch with if after, without new line can't be parsed
        // | switch
        // | cond => do
        // | cond => do // I wanna If here, but it will be another case
        if (isControlFlow) {
            messagesOrVarStatements.add(statementWithEndLine())
        } else {
            messagesOrVarStatements.add(statement())
        }
    }

    val realIsSingleExpression = isSingleExpression && messagesOrVarStatements[0] is Expression

    return Pair(messagesOrVarStatements, realIsSingleExpression)
}

// returns null if it's not a message declaration
// very bad function
// TODO refactor


// Int sas ^ (-> Type)? =?
@Suppress("UNUSED_VARIABLE")
fun Parser.isThereEndOfMessageDeclaration(isConstructor: Boolean): Boolean {
    var isThereReturn = false
    var isThereEqual = false

    val returnArrow = match(TokenType.ReturnArrow)
    if (returnArrow) {
        isThereReturn = true
        val type = identifierMayBeTyped()
    }
    match(TokenType.Return)
    val equal = match(TokenType.Assign)
    if (equal) isThereEqual = true

//    return

    return isThereReturn || isThereEqual || isConstructor
}

fun Parser.tryUnary(isConstructor: Boolean): Boolean {
    val savepoint = current

    if (check(TokenType.Identifier) && (!check(TokenType.DoubleColon, 1) && !check(TokenType.Identifier, 1))) {
        match(TokenType.Identifier)
        val isThereEndOfMsgDecl = isThereEndOfMessageDeclaration(isConstructor)
        if (isThereEndOfMsgDecl) return true
    }
    current = savepoint
    return false
}

fun Parser.tryBinary(isConstructor: Boolean): Boolean {
    val savepoint = current

    if (match(TokenType.BinarySymbol) && check(TokenType.Identifier)) {
        // + ^x::Type
        identifierMayBeTyped()
        // + x::Type^
        if (isThereEndOfMessageDeclaration(isConstructor))
            return true
    }


    current = savepoint
    return false
}


fun Parser.kwArgsAndEndOfMessageDeclaration(isConstructor: Boolean): Boolean {
    while (!(check(TokenType.Assign) || check(TokenType.ReturnArrow))) {
        try {
            skipNewLinesAndComments()
            if ((check(TokenType.Identifier) && check(TokenType.DoubleColon, 1)) ||
                (check(TokenType.Identifier) && check(TokenType.Colon, 1) && check(TokenType.Identifier, 2))
            ) {
                keyArg()

            } else {
                return isThereEndOfMessageDeclaration(isConstructor)
            }
        } catch (e: Exception) {
            return isThereEndOfMessageDeclaration(isConstructor)
        }
    }
    return isThereEndOfMessageDeclaration(isConstructor)

}

fun Parser.tryKeyword(isConstructor: Boolean): Boolean {
    val savepoint = current

    if (kwArgsAndEndOfMessageDeclaration(isConstructor)) {
        return true
    }


    current = savepoint
    return false
}

fun Parser.checkTypeOfMessageDeclaration2(isConstructor: Boolean = false, parseReceiver: Boolean = true): MessageDeclarationType? {
    val savepoint = current
    @Suppress("UNUSED_VARIABLE")
    val receiver = if (parseReceiver) identifierMayBeTyped() else null


    if (tryUnary(isConstructor)) {
        current = savepoint
        return MessageDeclarationType.Unary
    }

    if (tryKeyword(isConstructor)) {
        current = savepoint
        return MessageDeclarationType.Keyword
    }

    if (tryBinary(isConstructor)) {
        current = savepoint
        return MessageDeclarationType.Binary
    }

    current = savepoint

    return null
}

enum class MessageDeclarationType {
    Unary,
    Binary,
    Keyword
}

fun Parser.messageDeclaration(
    type: MessageDeclarationType,
    codeAttributes: MutableList<CodeAttribute>? = null,
    customForTypeAst: TypeAST? = null
): MessageDeclaration {

    val forTypeAst = customForTypeAst ?: parseType()
    val result = when (type) {
        MessageDeclarationType.Unary -> unaryDeclaration(forTypeAst)
        MessageDeclarationType.Binary -> binaryDeclaration(forTypeAst)
        MessageDeclarationType.Keyword -> keywordDeclaration(forTypeAst)
    }
    if (codeAttributes != null) {
        result.pragmas = codeAttributes
    }
    return result
}

fun Parser.extendDeclaration(pragmas: MutableList<CodeAttribute>): ExtendDeclaration {
    // extend Person [
    match("extend")

    val forTypeAst = parseType()
    skipNewLinesAndComments()
    matchAssert(TokenType.OpenBracket)
    skipNewLinesAndComments()


    val list = mutableListOf<MessageDeclaration>()
    do {
        val isItMsgDeclaration = checkTypeOfMessageDeclaration2(parseReceiver = false)
            ?: peek().compileError("Can't parse message declaration $RED${peek().lexeme}")

        val msgDecl = messageDeclaration(isItMsgDeclaration, pragmas, forTypeAst)
        list.add(msgDecl)
        skipNewLinesAndComments()
    } while (!match(TokenType.CloseBracket))


    return ExtendDeclaration(
        forTypeAst = forTypeAst,
        messageDeclarations = list,
        token =  forTypeAst.token
    )

}


// constructor TYPE messageDeclaration
fun Parser.constructorDeclaration(codeAttributes: MutableList<CodeAttribute>): ConstructorDeclaration {
    val constructorKeyword = matchAssert(TokenType.Constructor, "Constructor expected")

    val isItKeywordDeclaration =
        checkTypeOfMessageDeclaration2(true)//checkTypeOfMessageDeclaration(isConstructor = true)
    val msgDecl = if (isItKeywordDeclaration != null) {
        messageDeclaration(isItKeywordDeclaration, codeAttributes)
    } else null

    if (msgDecl == null) {
        error("message declaration after constructor expected")
    }

    val result = ConstructorDeclaration(
        msgDeclaration = msgDecl,
        constructorKeyword,
    )
    return result
}
