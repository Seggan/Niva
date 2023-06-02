package frontend.parser.types

import frontend.meta.Token


sealed class MessageDeclaration(
    val name: String,
    token: Token,
    val isSingleExpression: Boolean,
    val body: List<Statement>,
    val returnType: String?,
    isPrivate: Boolean = false,
    pragmas: List<Pragma> = listOf()
) : Statement(token, isPrivate, pragmas)

class MessageDeclarationUnary(
    name: String,
    token: Token,
    isSingleExpression: Boolean,
    body: List<Statement>,
    returnType: String?,
    isPrivate: Boolean = false,
    pragmas: List<Pragma> = listOf()
) : MessageDeclaration(name, token, isSingleExpression, body,returnType, isPrivate, pragmas)

class MessageDeclarationBinary(
    name: String,
    token: Token,
    val arg: KeywordDeclarationArg,
    body: List<Statement>,
    returnType: String?,
    isSingleExpression: Boolean,
    isPrivate: Boolean = false,
    pragmas: List<Pragma> = listOf()
) : MessageDeclaration(name, token, isSingleExpression, body, returnType,isPrivate, pragmas)


// key: localName::type
class KeywordDeclarationArg(
    val name: String,
    val localName: String? = null,
    val type: String? = null,
)

class MessageDeclarationKeyword(
    name: String,
    token: Token,
    val args: List<KeywordDeclarationArg>,
    body: List<Statement>,
    returnType: String?,
    isSingleExpression: Boolean,
    isPrivate: Boolean = false,
    pragmas: List<Pragma> = listOf(),
) : MessageDeclaration(name, token, isSingleExpression, body, returnType ,isPrivate, pragmas)
