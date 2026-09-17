package mylang.parser;

public sealed interface SwitchBranchBody extends AstNode
    permits SwitchBranchExpressionBody, SwitchBranchBlockBody {

}
