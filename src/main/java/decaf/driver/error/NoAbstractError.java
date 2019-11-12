package decaf.driver.error;

import decaf.frontend.tree.Pos;


public class NoAbstractError extends DecafError{
    private String name;

    public NoAbstractError(Pos pos, String name) {
        super(pos);
        this.name = name;
    }

    @Override
    protected String getErrMsg() {
        return "'"+name+"'" + " is not abstract and does not override all abstract methods";
    }
}
