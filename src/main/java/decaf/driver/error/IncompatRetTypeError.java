package decaf.driver.error;

import decaf.frontend.tree.Pos;

public class IncompatRetTypeError extends DecafError {

    public IncompatRetTypeError(Pos pos) {
        super(pos);
    }

    @Override
    protected String getErrMsg() {
        return "incompatible return types in blocked expression";
    }
}
