@file:Suppress("ControlFlowWithEmptyBody")

package frontend.parser.parsing

import frontend.parser.types.ast.KeyPragma
import frontend.parser.types.ast.Pragma
import frontend.parser.types.ast.SingleWordPragma
import main.frontend.meta.Position
import main.frontend.meta.Token
import main.frontend.meta.TokenType
import main.frontend.meta.compileError
import main.frontend.meta.isIdentifier
import main.frontend.parser.parsing.*
import main.frontend.parser.types.ast.*


// Declaration without end of line
fun Parser.statement(): Statement {
    val pragmas = if (check("@")) pragmas() else mutableListOf()
    val tok = peek()

    val kind = tok.kind


    if (kind == TokenType.Dot) {
        return expression(dot = true)
    }

    // x sas!! generates InfoToken x sas
    if (kind == TokenType.Bang) {
        val lastStatement = this.tree.lastOrNull()
        if (lastStatement is Expression) {
            tree.removeLast()
            return NeedInfo(lastStatement, step())
        } else {
            return NeedInfo(null, step())
        }
    }

    if (tok.isIdentifier() &&
        check(TokenType.Assign, 1) || check(TokenType.DoubleColon, 1)
        || kind == TokenType.Mut
    ) {
        val q = varDeclaration()
        if (q != null) return q
    }

    if (kind == TokenType.Type) {
        return if (checkMany(TokenType.Identifier, TokenType.Assign, distance = 1)) {
            typeAliasDeclaration(pragmas)
        } else
            typeDeclaration(pragmas)

    }
    if (kind == TokenType.Enum) {
        return enumDeclaration(pragmas)
    }
//    if (kind == TokenType.Alias) {
//        return typeAliasDeclaration()
//    }
    if (kind == TokenType.Union) {
        return unionDeclaration(pragmas)
    }
    if (kind == TokenType.ErrorDomain) {
        return errordomainDeclaration(pragmas)
    }


    if (kind == TokenType.Constructor) {
        return constructorDeclaration(pragmas)
    }

    if (kind == TokenType.Builder) {
        return builderDeclaration(pragmas)
    }
    // extend type with many methods
    if (tok.isIdentifier() && check(TokenType.Builder, 1)) {
        return builderDeclarationWithReceiver(pragmas)
    }

    if (kind == TokenType.Identifier && tok.lexeme == "extend") {
        return extendDeclaration(pragmas)
    }

    // extend type with many methods
    if (tok.isIdentifier() && check(TokenType.AssignArrow, 1)) {
        return assignVariableNewValue()
    }

    if (kind == TokenType.Assign && check(TokenType.CloseBrace, -1)) {
        return destructiveAssignParse(tok)
    }


    val isInlineReplWithNum = kind == TokenType.InlineReplWithNum
    val isInlineReplWithQuestion = kind == TokenType.InlineReplWithQuestion

    if (tok.lexeme == ">" || isInlineReplWithNum || isInlineReplWithQuestion) {
        val inlineTok = step()
        try {
            val inlineExpr = expression()

            inlineExpr.isInlineRepl = true
            if (isInlineReplWithNum)
                inlineExpr.inlineReplCounter = tok.lexeme.substring(1).toInt()
            else if (isInlineReplWithQuestion) {
                inlineExpr.isInfoRepl = true
            }


            return inlineExpr

        } catch (_: Exception) {
            inlineTok.compileError("> can only be used with expressions")
        }

    }

    if (kind == TokenType.Return) {
        val returnTok = step()
        val expression = if (checkEndOfLineOrFile()) null else expression(parseSingleIf = true)
        return ReturnStatement(
            expression = expression,
            token = returnTok,
        )
    }

    if (kind == TokenType.EndOfFile) {
        tok.compileError("Nothing to compile :(")
    }

    val isItMsgDeclaration = checkTypeOfMessageDeclaration2()
    if (isItMsgDeclaration != null) {
        return messageDeclaration(isItMsgDeclaration, pragmas)
    }

    skipNewLinesAndComments()
    return expression(parseSingleIf = true)
}

fun Parser.dotSeparatedIdentifiers(): IdentifierExpr? {
    val x = step()
    if (x.kind != TokenType.Identifier) {
        return null
    }
    val dotMatched = match(TokenType.Dot)
    val listOfIdentifiersPath = mutableListOf(x.lexeme)
    if (dotMatched) {
        do {
            val q = matchAssert(TokenType.Identifier, "Identifier expected after dot, but found ${peek().lexeme}")
            listOfIdentifiersPath.add(q.lexeme)
        } while (match(TokenType.Dot))
    }

    return IdentifierExpr(listOfIdentifiersPath.last(), listOfIdentifiersPath, null, x)
}

// if inside var decl with type, then we're getting type from it
fun Parser.identifierMayBeTyped(typeAST: TypeAST? = null): IdentifierExpr {

    val x = matchAssertOr(TokenType.Identifier, TokenType.NullableIdentifier)
    var lastTokenDotSeparated: Token? = null
    val dotMatched = match(TokenType.Dot)
    val listOfIdentifiersPath = mutableListOf(x.lexeme)
    if (dotMatched) {
        do {
            val q = matchAssert(TokenType.Identifier, "Identifier expected after dot, but found ${peek().lexeme}")
            listOfIdentifiersPath.add(q.lexeme)
            lastTokenDotSeparated = q
        } while (match(TokenType.Dot))
    }

    // change end tok for org.sas.sus.Button
    if (listOfIdentifiersPath.count() > 1 && lastTokenDotSeparated != null) {
        x.relPos.end = lastTokenDotSeparated.relPos.end
    }

    val isTyped = match(TokenType.DoubleColon)
    return if (isTyped) {
        val type = parseType()
        IdentifierExpr(listOfIdentifiersPath.last(), listOfIdentifiersPath, type, x)
    } else {
        IdentifierExpr(listOfIdentifiersPath.last(), listOfIdentifiersPath, typeAST, x) // look for type in table
    }
}

fun Parser.primary(typeAST: TypeAST? = null): Primary? {
    return when (peek().kind) {
        TokenType.Identifier, TokenType.NullableIdentifier -> identifierMayBeTyped(typeAST)
        TokenType.True -> LiteralExpression.TrueExpr(step())
        TokenType.False -> LiteralExpression.FalseExpr(step())
        TokenType.Null -> LiteralExpression.NullExpr(typeAST ?: TypeAST.InternalType(InternalTypes.Any, peek()), step())
        TokenType.Integer -> LiteralExpression.IntExpr(step())
        TokenType.Float -> LiteralExpression.FloatExpr(step())
        TokenType.Double -> LiteralExpression.DoubleExpr(step())
        TokenType.String -> LiteralExpression.StringExpr(step())
        TokenType.Char -> LiteralExpression.CharExpr(step())

        else -> null
    }
}


fun Parser.assignVariableNewValue(): Assign {
    // x <- expression
    val identTok = this.step()
    matchAssert(TokenType.AssignArrow)

    val isNextReceiver = isNextSimpleReceiver()
    val value = if (isNextReceiver) simpleReceiver() else expression()


    val result = Assign(identTok, identTok.lexeme, value)

    return result

}


fun Token.isPrimaryToken(): Boolean =
    when (kind) {
        TokenType.Identifier,
        TokenType.True,
        TokenType.False,
        TokenType.Integer,
        TokenType.Float,
        TokenType.Double,
        TokenType.Char,
        TokenType.String,
        -> true

        else -> false
    }

// checks is next thing is receiver
// needed for var declaration to know what to parse - message or value
fun Parser.isNextSimpleReceiver(): Boolean {
    val savepoint = current
    if (peek().isPrimaryToken()) {
        // x = 1
        // from: 0
        // to: 3
        if (check(TokenType.EndOfLine, 1) && check(TokenType.Identifier, 2) && check(TokenType.Colon, 3)) {
            identifierMayBeTyped()
            skipNewLinesAndComments()
            if (check(TokenType.Identifier) && check(TokenType.Colon, 1)) {
                current = savepoint
                return false
            }
        }
        when {

            // x = 1
            check(TokenType.EndOfLine, 1) || check(TokenType.EndOfFile, 1) -> return true
            // x = [code]
            check(TokenType.OpenBracket, 1) -> return true
            check(TokenType.OpenParen, 1) -> return true
            check(TokenType.OpenBrace, 1) -> return true
        }
    }

    return false
}

fun Parser.commaSeparatedExpressions(): List<Expression> {
    val result = mutableListOf<Expression>()
    do {
        result.add(expression())
    } while (match(TokenType.Comma))
    return result
}

// message or control flow or static builder
// inside `x from: y to: z`
// we don't have to parse `y to: z` as new keyword, only y expression
fun Parser.expression(
    dontParseKeywordsAndUnaryNewLines: Boolean = false, // true if it's a keyword argument to prevent stack overflow of parsing keywords inside keywords
    dot: Boolean = false,
    parseSingleIf: Boolean = false // TODO replace on checking root, make root always required
): Expression {

    if (check(TokenType.If)) {
        return switchStatementOrExpression()
    }

    // replace underscore with second If token, so matching on emptyness
    if (check(TokenType.Underscore)) {
        return ifStatementOrExpression()
    }

    if (match(TokenType.Ampersand)) {
        return methodReference()
    }


    fun tryMessageReference(): MethodReference? {
        val savepoint = this.current
        try {
            val receiver = parseType()
            return if (receiver is TypeAST.Lambda && receiver.extensionOfType != null) {
                val methodReference =
                    receiver.toMethodReference(receiver.extensionOfType)//lambda.toMethodReference(receiver)
                methodReference
            } else {
                current = savepoint
                null
            }
        } catch (_: Exception) {
            current = savepoint
            return null
        }
    }

    val methodReference = tryMessageReference()

    if (methodReference != null) {
        return methodReference
    }
    val messageSend = messageSend(dontParseKeywordsAndUnaryNewLines, dot)

    val fixPosition = { m: MessageSend ->
        if (m.messages.isNotEmpty()) {
            m.token.pos.end = m.messages.last().token.pos.end
        }
    }
    // unwrap unnecessary MessageSend when it's a single receiver like `person`
    val unwrapped = if (messageSend.messages.isEmpty() && messageSend is MessageSendUnary) {
        if (messageSend.receiver is MessageSend)
            fixPosition(messageSend.receiver)
        messageSend.receiver
    } else {
        fixPosition(messageSend)
        messageSend
    }


    // x > 5 ^ => ...
    if (parseSingleIf && match(TokenType.Then)) {
        var codeBlock: CodeBlock? = null
        var singleExpr: Statement? = null
        if (check(TokenType.OpenBracket)) {
            codeBlock = codeBlock()
        } else {
            singleExpr = statementWithEndLine()
        }

        val isSingleExpression = singleExpr is Expression


        skipNewLinesAndComments()
        val elseBranch = if (match(TokenType.Else)) {
            methodBody(true).first.toList()
        } else null

        val singleIf = ControlFlow.If(
            type = null,
            ifBranches = listOf(
                if (isSingleExpression) {

                    IfBranch.IfBranchSingleExpr(
                        ifExpression = unwrapped,
                        thenDoExpression = singleExpr,
                        emptyList()
                    )
                } else {
                    // this single expression is statement
                    val body = if (singleExpr != null) {
                        // codeBlock With single expr
                        CodeBlock(
                            inputList = emptyList(),
                            statements = listOf(singleExpr),
                            type = null,
                            token = singleExpr.token
                        )
                    } else {
                        codeBlock!!
                    }
                    IfBranch.IfBranchWithBody(
                        ifExpression = unwrapped,
                        body = body,
                        emptyList()
                    )
                }
            ),
            kind = ControlFlowKind.Statement,
            elseBranch = elseBranch,
            token = unwrapped.token,
        )

        return singleIf
    }

    return unwrapped
}

fun Parser.parseDocComment(): DocComment? {
    data class WordInfo(val word: String, val startIndex: Int, val endIndex: Int, val lineNum: Int)

    fun extractWordsWithIndices(input: String): List<WordInfo> {
        val regex = Regex("@(\\w+)")
        val result = mutableListOf<WordInfo>()

        input.split("\n").forEachIndexed { lineNum, line ->
            val matches = regex.findAll(line)
            matches.forEachIndexed { i, matchResult ->
                if (matchResult.groupValues.count() > 1) {
                    val word = matchResult.groupValues[1]
                    val startIndex = matchResult.range.first
                    val endIndex = matchResult.range.last
                    result.add(WordInfo(word, startIndex, endIndex, lineNum = lineNum))
                }
            }
        }

        return result
    }

    if (check(TokenType.DocComment)) {
        val docComment = step()
        val words = extractWordsWithIndices(docComment.lexeme)
        val text = docComment.lexeme.replace("///", "").trimIndent()

        val identifiers = words.map {
            val name = it.word
            val token = Token(
                TokenType.Identifier,
                name,
                line = docComment.line + it.lineNum,
                Position(0, 1),
                Position(it.startIndex + 1, it.endIndex + 1),
                docComment.file,
                0,
//                lineEnd = //if (words.isNotEmpty()) words.last().lineNum + docComment.line else 0
            )


            IdentifierExpr(name, listOf(name), null, token, true)
        }

        return DocComment(
            text = text,
            identifiers = identifiers,
        )
    }
    return null
}

fun Parser.pragmas(): MutableList<Pragma> {
    val pragmas: MutableList<Pragma> = mutableListOf()
    while (check("@")) {
        step() // skip @

        if (checkMany(TokenType.Identifier, TokenType.Colon))
        // @sas: 1 sus: 2
            do {
                val name = step()
                matchAssert(TokenType.Colon)
                val value = primary() ?: name.compileError("Inside code attribute after : value expected")

                pragmas.add(
                    KeyPragma(
                        name = name.lexeme,
                        value = value
                    )
                )

            } while (check(TokenType.Identifier) && check(TokenType.Colon, 1))
        else {
            // @Sas Sus
            do {
                val name = step().lexeme
                pragmas.add(SingleWordPragma(name))
            } while (check(TokenType.Identifier))
        }
        skipOneEndOfLineOrComment()
    }
    return pragmas
}


fun Parser.statementWithEndLine(): Statement {
    skipNewLinesAndComments()
    val docComment = parseDocComment()
    val result = this.statement()
        .also { if (docComment != null) it.docComment = docComment }
    skipNewLinesAndComments()

    return result
}

fun Parser.statements(): List<Statement> {

    while (!this.done()) {
        this.tree.add(this.statementWithEndLine())
    }

    return this.tree
}


fun Parser.checkEndOfLineOrFile(i: Int = 0) =
    check(TokenType.EndOfLine, i) || check(TokenType.EndOfFile, i) || check(TokenType.Comment)


fun Parser.skipOneEndOfLineOrComment() =
    match(TokenType.EndOfLine) || match(TokenType.Comment)

fun Parser.skipNewLinesAndComments() {
    while (match(TokenType.EndOfLine) || match(TokenType.Comment)) {
        if (check(TokenType.EndOfFile)) break
    }
}
