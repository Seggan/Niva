import frontend.parser.*
import org.testng.annotations.Test

class ParserTest {
    @Test
    fun varDeclaration() {
        val source = "x::int = 1"
        val ast = getAst(source)
        println("ast.count = ${ast.count()}")
        assert(ast.count() == 1)
        println("ast = $ast")
        println("ast[0] = ${ast[0]}")

        val declaration: VarDeclaration = ast[0] as VarDeclaration
        assert(declaration.name.str == "x")
        assert(declaration.value.type == "int")
        assert(declaration.value.str == "1")
    }

    @Test
    fun varDeclWithTypeInfer() {
        val source = "x = 1"
        val ast = getAst(source)
        assert(ast.count() == 1)

        val declaration: VarDeclaration = ast[0] as VarDeclaration
        assert(declaration.name.str == "x")
        assert(declaration.value.type == "int")
        assert(declaration.value.str == "1")
    }

    @Test
    fun string() {
        val source = "\"sas\""
        val ast = getAst(source)
        assert(ast.count() == 1)

        val declaration = ast[0] as UnaryMsg

        assert(declaration.str == "\"sas\"")
    }


    @Test
    fun helloWorld() {
        val source = "\"sas\" echo"
        val ast = getAst(source)
        assert(ast.count() == 1)

        val unaryMsg = ast[0] as UnaryMsg

        assert(unaryMsg.selectorName == "echo")
        assert(unaryMsg.receiver.type == "string")
        assert(unaryMsg.receiver.str == "\"sas\"")
    }


    @Test
    fun varDeclAndUnaryMsg() {
        val source = """
        x = 1
        x echo
    """.trimIndent()
        val ast = getAst(source)
        assert(ast.count() == 2)

        val declaration = ast[0] as VarDeclaration
        val unaryMsg = ast[1] as UnaryMsg
        assert(declaration.name.str == "x")
        assert(declaration.value.type == "int")
        assert(declaration.value.str == "1")

        assert(unaryMsg.selectorName == "echo")
//        assert(unaryMsg.receiver.type == "int")
        assert(unaryMsg.receiver.str == "x")
    }

    @Test
    fun twoUnary() {
        val source = "3 inc inc"
        val ast = getAst(source)
        assert(ast.count() == 1)

        val unary: MessageCall = ast[0] as MessageCall
        assert(unary.messages.count() == 2)
        assert(unary.messages[0].selectorName == "inc")
        assert(unary.messages[1].selectorName == "inc")
        assert(unary.messages[1].receiver.type == "int")
    }

    @Test
    fun binaryWithUnary() {
        // inc(inc(3)) + dec(dec(2))
        val source = "3 inc inc + 2 dec dec"
        val ast = getAst(source)
        assert(ast.count() == 1)

        val messageCall: MessageCall = ast[0] as MessageCall
        val messages = messageCall.messages
        assert(messages.count() == 1)

        val binaryMsg = messages[0] as BinaryMsg
        assert(binaryMsg.receiver.str == "3")
        assert(binaryMsg.argument.str == "2")

        assert(binaryMsg.unaryMsgsForReceiver.count() == 2)
        assert(binaryMsg.unaryMsgsForArg.count() == 2)

        assert(binaryMsg.unaryMsgsForReceiver[0].selectorName == "inc")
        assert(binaryMsg.unaryMsgsForReceiver[1].selectorName == "inc")

        assert(binaryMsg.unaryMsgsForArg[0].selectorName == "dec")
        assert(binaryMsg.unaryMsgsForArg[1].selectorName == "dec")

    }

    @Test
    fun keywordMessage() {
        // inc(inc(3)) + dec(dec(2))
        val source = "x from: 3 inc inc + 2 dec dec to: 5"
        val ast = getAst(source)
        assert(ast.count() == 1)

        val unary: MessageCall = ast[0] as MessageCall
        val messages = unary.messages
        assert(messages.count() == 1)
        val keywordMsg = messages[0] as KeywordMsg

        assert(keywordMsg.args.count() == 2)
        assert(keywordMsg.args[0].keywordArg.type == "int")
        assert(keywordMsg.args[0].keywordArg.str == "3")
        assert(keywordMsg.args[0].unaryOrBinaryMsgsForArg.count() == 1)
        assert(keywordMsg.args[0].unaryOrBinaryMsgsForArg[0] is BinaryMsg)
        val binaryFromKeyword = keywordMsg.args[0].unaryOrBinaryMsgsForArg[0] as BinaryMsg
        assert(binaryFromKeyword.selectorName == "+")
        assert(binaryFromKeyword.unaryMsgsForArg.count() == 2)
        assert(binaryFromKeyword.unaryMsgsForReceiver.count() == 2)

        assert(keywordMsg.args[1].keywordArg.type == "int")
        assert(keywordMsg.args[1].keywordArg.str == "5")
        assert(keywordMsg.args[1].unaryOrBinaryMsgsForArg.isEmpty())
    }

//    @Test
//    fun binaryMsg() {
//        val source = "2 + 1 - 3"
//        val ast = declarationList(source)
//        assert(ast.count() == 2)
//
//        val binaryMsg = ast[0] as BinaryMsg
//
//        assert(binaryMsg.selectorName == "+")
//        assert(binaryMsg.receiver.type == "int")
//        assert(binaryMsg.receiver.str == "2")
//
//        val binaryMsg2 = ast[0] as BinaryMsg
//    }


}

fun getAst(source: String): List<Declaration> {
    val tokens = lex(source)
    val parser = Parser(file = "", tokens = tokens, source = "sas.niva")
    val ast = parser.declarations()
    return ast
}
