package decaf.driver.error;
import decaf.frontend.tree.Pos;


public class BadInstantiateError extends DecafError{
    private String name;

    public BadInstantiateError(Pos pos, String name) {
        super(pos);
        this.name = name;
    }

    @Override
    protected String getErrMsg() {
        return "cannot instantiate abstract class "+"'"+name+"'";
    }
}
