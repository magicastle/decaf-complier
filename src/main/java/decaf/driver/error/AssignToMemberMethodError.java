package decaf.driver.error;

import decaf.frontend.tree.Pos;

public class AssignToMemberMethodError extends DecafError{
    private String name;

    public AssignToMemberMethodError(Pos pos, String name){
        super(pos);
        this.name = name;
    }
    @Override
    protected String getErrMsg() {
        return "cannot assign value to class member method '"+ this.name+"'";
    }
}
