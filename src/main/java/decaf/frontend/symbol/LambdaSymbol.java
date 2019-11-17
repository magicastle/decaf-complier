package decaf.frontend.symbol;

import decaf.frontend.scope.FormalScope;
import decaf.frontend.scope.LambdaScope;
import decaf.frontend.scope.LocalScope;
import decaf.frontend.tree.Pos;
import decaf.frontend.tree.Tree;
import decaf.frontend.type.FunType;

public class LambdaSymbol extends Symbol {

    public final LambdaScope lambdaScope;
    public final LocalScope localScope;

    public LambdaSymbol(FunType type, LambdaScope lambdaScope, LocalScope localScope, Pos pos) {
        super("lambda@"+pos, type, pos);
        this.lambdaScope = lambdaScope;
        this.localScope = localScope;
        lambdaScope.setOwner(this);
    }

    @Override
    public boolean isLambdaSymbol() {
        return true;
    }
    @Override
    protected String str() {
        return String.format("function %s : %s", name, type);
    }
}
