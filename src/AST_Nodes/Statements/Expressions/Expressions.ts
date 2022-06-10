import { MessageCall } from "./Messages/Message";
import { Receiver } from "./Receiver/Receiver";

export type Expression = MessageCallExpression | BracketExpression | SwitchExpression


interface BaseMessageCallExpression {
	receiver: Receiver;
	messageCalls: MessageCall[];
}

export interface MessageCallExpression extends BaseMessageCallExpression {
  kindStatement: "MessageCallExpression"
}

export interface BracketExpression extends BaseMessageCallExpression {
	kindStatement: "BracketExpression"
}

export interface SwitchExpression  {
	kindStatement: "SwitchExpression"
	branches: SwitchBranch[]
  elseBranch?: ElseBranch
}

export interface SwitchBranch {
	caseExpression: Expression,
	thenDoExpression: Expression,
}

export interface ElseBranch{
	thenDoExpression: Expression,
}
