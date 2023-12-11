import frontend.Lexer
import frontend.lex
import frontend.meta.TokenType
import frontend.meta.TokenType.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals


val helloWorldProgram = """
"Hello w" echo
""".trimIndent()


val rawString = """
x = r"string"
""".trimIndent()

class LexerTest {

    @Test
    fun identifierColon() {
        val manyExpr = "sas:"
        checkWithEnd(manyExpr, listOf(Identifier, Colon))
    }


    @Test
    fun manyExpr() {
        val manyExpr = """
            x sas
            y sus
        """.trimIndent()
        checkWithEnd(manyExpr, listOf(Identifier, Identifier, EndOfLine, Identifier, Identifier))
    }

    @Test
    fun oneManyLinesExpr() {
        val oneExpr = """x sas: 1
  .ses: 2
x sas
"""
        // there no end of line after "sas" because there end of file
        checkWithEnd(
            oneExpr, listOf(
                Identifier,
                Identifier,
                Colon,
                Integer,
                Dot,
                Identifier,
                Colon,
                Integer,
                EndOfLine,
                Identifier,
                Identifier,
                EndOfFile
            )
        )
    }

    @Test
    fun emptySource() {
        checkWithEnd("", listOf(EndOfFile))
    }

    @Test
    fun string() {
        checkWithEnd("\"sas\"", listOf(TokenType.String))
    }

    @Test
    fun helloWorld() {
        checkWithEnd(helloWorldProgram, listOf(TokenType.String, Identifier))
    }

    @Test
    fun createVariable() {
        checkWithEnd("x = 42", listOf(Identifier, Assign, Integer))
    }

    @Test
    fun typedVar() {
        checkWithEnd("x::int", listOf(Identifier, DoubleColon, Identifier))
    }

    @Test
    fun sass() {
        checkWithEnd("|=>", listOf(Else))
    }

    @Test
    fun singleIdentifier() {
        checkWithEnd("sas", listOf(Identifier))
    }

    @Test
    fun rawString() {
        checkWithEnd(rawString, listOf(Identifier, Assign, TokenType.String))
    }

    @Test
    fun functionDeclarationWithBody() {

        val functionDeclaration = """
            int to: x = [
              x echo
            ]       
        """.trimIndent()
        checkWithEnd(
            functionDeclaration, listOf(
                Identifier,
                Identifier,
                Colon,
                Identifier,
                Assign,
                OpenBracket,
                EndOfLine,
                Identifier,
                Identifier,
                EndOfLine,
                CloseBracket,
                EndOfFile
            )
        )
    }

    @Test
    fun brackets() {
        checkWithEnd("{} () []", listOf(OpenBrace, CloseBrace, OpenParen, CloseParen, OpenBracket, CloseBracket))
    }

    @Test
    fun keywords() {
        checkWithEnd("true false type use union constructor", listOf(True, False, Type, Use, Union, Constructor))
    }

    @Test
    fun hardcodedBinarySymbols() {
        checkWithEnd(
            "^ |> | |=> = ::", listOf(
                Return, PipeOperator, Pipe, Else, Assign, DoubleColon
            )
        )
    }

    @Test
    fun binarySymbols2() {
        checkWithEnd(
            "|| && == !=", listOf(
                BinarySymbol, BinarySymbol, BinarySymbol, BinarySymbol
            )
        )
    }

    @Test
    fun punctuation() {
        checkWithEnd(". ; , : ", listOf(Dot, Cascade, Comma, Colon))
    }

    @Test
    fun pipeOperator() {
        checkWithEnd("|>", listOf(PipeOperator))
        checkWithEnd("|||", listOf(BinarySymbol, Pipe)) // || is OR
    }

    @Test
    fun comment() {
        checkWithEnd("// some important info", listOf(Comment))
    }

    @Test
    fun typeAlias() {
        checkWithEnd("alias", listOf(Alias))
    }

    @Test
    fun nn() {
        checkWithEnd(
            """
                    min
    
                    ^
            """.trimIndent(), listOf(Identifier, EndOfLine, EndOfLine, Return)
        )
    }

    @Test
    fun dotDotOp() {
        checkWithEnd("1..2", listOf(Integer, BinarySymbol, Integer))
    }

    @Test
    fun codeAttributes() {
        checkWithEnd(
            """
                @ a: 1 b: "sas"
                type Person
            """.trimIndent(), listOf(
                BinarySymbol,
                Identifier,
                Colon,
                Integer,
                Identifier,
                Colon,
                TokenType.String,
                EndOfLine,
                Type,
                Identifier,
            )
        )
    }

    @Test
    fun inlineQuestion() {
        val manyExpr = ">?"
        checkWithEnd(manyExpr, listOf(InlineReplWithQuestion))
    }


    private fun checkWithEnd(source: String, tokens: List<TokenType>, showTokens: Boolean = true) {
        val lexer = Lexer(source, File("Niva.iml"))
//        lexer.fillSymbolTable()
        val result = lexer.lex().map { it.kind }
            .dropLast(1) // drop end of file
        assertEquals(tokens, result)
        if (showTokens) {
            println("$result")
        }
    }
}
