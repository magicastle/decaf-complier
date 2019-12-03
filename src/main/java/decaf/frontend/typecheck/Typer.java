package decaf.frontend.typecheck;

import decaf.driver.Config;
import decaf.driver.Phase;
import decaf.driver.error.*;
import decaf.frontend.scope.Scope;
import decaf.frontend.scope.ScopeStack;
import decaf.frontend.symbol.ClassSymbol;
import decaf.frontend.symbol.MethodSymbol;
import decaf.frontend.symbol.VarSymbol;
import decaf.frontend.tree.Pos;
import decaf.frontend.tree.Tree;
import decaf.frontend.type.*;
import decaf.lowlevel.log.IndentPrinter;
import decaf.lowlevel.log.Log;
import decaf.printing.PrettyScope;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Optional;

/**
 * The typer phase: type check abstract syntax tree and annotate nodes with inferred (and checked) types.
 */
public class Typer extends Phase<Tree.TopLevel, Tree.TopLevel> implements TypeLitVisited {

    public Typer(Config config) {
        super("typer", config);
    }

    @Override
    public Tree.TopLevel transform(Tree.TopLevel tree) {
        var ctx = new ScopeStack(tree.globalScope);
        tree.accept(this, ctx);
        return tree;
    }

    @Override
    public void onSucceed(Tree.TopLevel tree) {
        if (config.target.equals(Config.Target.PA2)) {
            var printer = new PrettyScope(new IndentPrinter(config.output));
            printer.pretty(tree.globalScope);
            printer.flush();
        }
    }

    @Override
    public void visitTopLevel(Tree.TopLevel program, ScopeStack ctx) {
        for (var clazz : program.classes) {
            clazz.accept(this, ctx);
        }
    }

    @Override
    public void visitClassDef(Tree.ClassDef clazz, ScopeStack ctx) {
        ctx.open(clazz.symbol.scope);
        for (var field : clazz.fields) {
            field.accept(this, ctx);
        }
        ctx.close();
    }

    @Override
    public void visitMethodDef(Tree.MethodDef method, ScopeStack ctx) {
        ctx.open(method.symbol.scope);
        if(method.body != null) {
            method.body.accept(this, ctx);
            if (!method.symbol.type.returnType.isVoidType() && !method.body.returns) {
                issue(new MissingReturnError(method.body.pos));
            }
        }
        ctx.close();
    }

    /**
     * To determine if a break statement is legal or not, we need to know if we are inside a loop, i.e.
     * loopLevel {@literal >} 1?
     * <p>
     * Increase this counter when entering a loop, and decrease it when leaving a loop.
     */
    private int loopLevel = 0;

    @Override
    public void visitBlock(Tree.Block block, ScopeStack ctx) {
        ctx.open(block.scope);
        for (var stmt : block.stmts) {
            stmt.accept(this, ctx);
        }
        ctx.close();
        block.returns = !block.stmts.isEmpty() && block.stmts.get(block.stmts.size() - 1).returns;
        //设置close属性
        for(var stmt: block.stmts){
            if(stmt.isClose){
                block.isClose = true;
                break;
            }
        }
    }

    @Override
    public void visitAssign(Tree.Assign stmt, ScopeStack ctx) {
        stmt.lhs.accept(this, ctx);
        stmt.rhs.accept(this, ctx);
        var lt = stmt.lhs.type;
        var rt = stmt.rhs.type;
        // 不能对成员方法赋值
        if(stmt.lhs instanceof Tree.VarSel && ((Tree.VarSel)stmt.lhs).isMemberMethodName) {
            issue(new AssignToMemberMethodError(stmt.pos, ((Tree.VarSel)stmt.lhs).name));
        }

        if (lt.noError() && (!rt.subtypeOf(lt))) {
            issue(new IncompatBinOpError(stmt.pos, lt.toString(), "=", rt.toString()));
        }
        /*不能对捕获的外层的 非类作用域 中的符号直接赋值，
        * 但如果传入的是一个 对象或数组的 引用，可以通过该 引用 修改类的成员或数组元素。
        */
        if(lt.noError()){
            var currFuncScope = ctx.FormalOrLambdaScope();
            if(currFuncScope.isLambdaScope()&& stmt.lhs instanceof Tree.VarSel){
                //直接赋值的情况（没有引用）
                if(((Tree.VarSel) stmt.lhs).receiver.isEmpty()){
                    //在lambda块作用域和类作用域之间查找该符号：
                    ListIterator<Scope> iter = ctx.scopeStack.listIterator(ctx.scopeStack.size());
                    //先追溯到所在的函数或lambdablock
                    while(iter.hasPrevious()){
                        var scope =iter.previous();
                        if(scope == currFuncScope){
                            break;
                        }
                    }
                    //判断上一层如果不是类（说明这是在一个lambda block中）
                    while (iter.hasPrevious()){
                        var scope = iter.previous();
                        if(!scope.isClassScope() && ((Tree.VarSel) stmt.lhs).symbol.domain()==scope){
                            issue(new AssignToCapturedVarError(stmt.pos));
                            break;
                        }
                    }
                }
            }
        }
    }

    @Override
    public void visitExprEval(Tree.ExprEval stmt, ScopeStack ctx) {
        stmt.expr.accept(this, ctx);
    }


    @Override
    public void visitIf(Tree.If stmt, ScopeStack ctx) {
        checkTestExpr(stmt.cond, ctx);
        stmt.trueBranch.accept(this, ctx);
        stmt.falseBranch.ifPresent(b -> b.accept(this, ctx));
        // if-stmt returns a value iff both branches return
        stmt.returns = stmt.trueBranch.returns && stmt.falseBranch.isPresent() && stmt.falseBranch.get().returns;
        //
        if(ctx.FormalOrLambdaScope().isLambdaScope()){
            stmt.isClose=stmt.trueBranch.isClose && stmt.falseBranch.isPresent() && stmt.falseBranch.get().isClose;
        }
    }

    @Override
    public void visitWhile(Tree.While loop, ScopeStack ctx) {
        checkTestExpr(loop.cond, ctx);
        loopLevel++;
        loop.body.accept(this, ctx);
        loopLevel--;
        //
        if(ctx.FormalOrLambdaScope().isLambdaScope()) {
            loop.isClose=loop.body.isClose;
        }
    }

    @Override
    public void visitFor(Tree.For loop, ScopeStack ctx) {
        ctx.open(loop.scope);
        loop.init.accept(this, ctx);
        checkTestExpr(loop.cond, ctx);
        loop.update.accept(this, ctx);
        loopLevel++;
        for (var stmt : loop.body.stmts) {
            stmt.accept(this, ctx);
        }
        loopLevel--;
        //
        if(ctx.FormalOrLambdaScope().isLambdaScope()) {
            loop.isClose=loop.body.isClose;
        }
        ctx.close();
    }

    @Override
    public void visitBreak(Tree.Break stmt, ScopeStack ctx) {
        if (loopLevel == 0) {
            issue(new BreakOutOfLoopError(stmt.pos));
        }
    }

    @Override
    public void visitReturn(Tree.Return stmt, ScopeStack ctx) {
        var scope = ctx.FormalOrLambdaScope();
        stmt.expr.ifPresent(e -> e.accept(this, ctx));
        var actual = stmt.expr.map(e -> e.type).orElse(BuiltInType.VOID);
        if(scope.isFormalScope()){
            var expected = ctx.currentMethod().type.returnType;
            if (actual.noError() && !actual.subtypeOf(expected)) {
                issue(new BadReturnTypeError(stmt.pos, expected.toString(), actual.toString()));
            }
        }
        else if(scope.isLambdaScope()){
            stmt.isClose = true;
            typeListStack.get(typeListStack.size()-1).add(actual);
        }
        stmt.returns = stmt.expr.isPresent();
    }

    @Override
    public void visitPrint(Tree.Print stmt, ScopeStack ctx) {
        int i = 0;
        for (var expr : stmt.exprs) {
            expr.accept(this, ctx);
            i++;
            if (expr.type.noError() && !expr.type.isBaseType()) {
                issue(new BadPrintArgError(expr.pos, Integer.toString(i), expr.type.toString()));
            }
        }
    }

    private void checkTestExpr(Tree.Expr expr, ScopeStack ctx) {
        expr.accept(this, ctx);
        if (expr.type.noError() && !expr.type.eq(BuiltInType.BOOL)) {
            issue(new BadTestExpr(expr.pos));
        }
    }

    // Expressions

    @Override
    public void visitIntLit(Tree.IntLit that, ScopeStack ctx) {
        that.type = BuiltInType.INT;
    }

    @Override
    public void visitBoolLit(Tree.BoolLit that, ScopeStack ctx) {
        that.type = BuiltInType.BOOL;
    }

    @Override
    public void visitStringLit(Tree.StringLit that, ScopeStack ctx) {
        that.type = BuiltInType.STRING;
    }

    @Override
    public void visitNullLit(Tree.NullLit that, ScopeStack ctx) {
        that.type = BuiltInType.NULL;
    }

    @Override
    public void visitReadInt(Tree.ReadInt readInt, ScopeStack ctx) {
        readInt.type = BuiltInType.INT;
    }

    @Override
    public void visitReadLine(Tree.ReadLine readStringExpr, ScopeStack ctx) {
        readStringExpr.type = BuiltInType.STRING;
    }

    @Override
    public void visitUnary(Tree.Unary expr, ScopeStack ctx) {
        expr.operand.accept(this, ctx);
        var t = expr.operand.type;
        if (t.noError() && !compatible(expr.op, t)) {
            // Only report this error when the operand has no error, to avoid nested errors flushing.
            issue(new IncompatUnOpError(expr.pos, Tree.opStr(expr.op), t.toString()));
        }

        // Even when it doesn't type check, we could make a fair guess based on the operator kind.
        // Let's say the operator is `-`, then one possibly wants an integer as the operand.
        // Once he/she fixes the operand, according to our type inference rule, the whole unary expression
        // must have type int! Thus, we simply _assume_ it has type int, rather than `NoType`.
        expr.type = resultTypeOf(expr.op);
    }

    public boolean compatible(Tree.UnaryOp op, Type operand) {
        return switch (op) {
            case NEG -> operand.eq(BuiltInType.INT); // if e : int, then -e : int
            case NOT -> operand.eq(BuiltInType.BOOL); // if e : bool, then !e : bool
        };
    }

    public Type resultTypeOf(Tree.UnaryOp op) {
        return switch (op) {
            case NEG -> BuiltInType.INT;
            case NOT -> BuiltInType.BOOL;
        };
    }

    @Override
    public void visitBinary(Tree.Binary expr, ScopeStack ctx) {
        expr.lhs.accept(this, ctx);
        expr.rhs.accept(this, ctx);
        var t1 = expr.lhs.type;
        var t2 = expr.rhs.type;

        if (t1.noError() && t2.noError() && !compatible(expr.op, t1, t2)) {
            issue(new IncompatBinOpError(expr.pos, t1.toString(), Tree.opStr(expr.op), t2.toString()));
        }
        expr.type = resultTypeOf(expr.op);
    }

    public boolean compatible(Tree.BinaryOp op, Type lhs, Type rhs) {
        if (op.compareTo(Tree.BinaryOp.ADD) >= 0 && op.compareTo(Tree.BinaryOp.MOD) <= 0) { // arith
            // if e1, e2 : int, then e1 + e2 : int
            return lhs.eq(BuiltInType.INT) && rhs.eq(BuiltInType.INT);
        }

        if (op.equals(Tree.BinaryOp.AND) || op.equals(Tree.BinaryOp.OR)) { // logic
            // if e1, e2 : bool, then e1 && e2 : bool
            return lhs.eq(BuiltInType.BOOL) && rhs.eq(BuiltInType.BOOL);
        }

        if (op.equals(Tree.BinaryOp.EQ) || op.equals(Tree.BinaryOp.NE)) { // eq
            // if e1 : T1, e2 : T2, T1 <: T2 or T2 <: T1, then e1 == e2 : bool
            return lhs.subtypeOf(rhs) || rhs.subtypeOf(lhs);
        }

        // compare
        // if e1, e2 : int, then e1 > e2 : bool
        return lhs.eq(BuiltInType.INT) && rhs.eq(BuiltInType.INT);
    }

    public Type resultTypeOf(Tree.BinaryOp op) {
        if (op.compareTo(Tree.BinaryOp.ADD) >= 0 && op.compareTo(Tree.BinaryOp.MOD) <= 0) { // arith
            return BuiltInType.INT;
        }
        return BuiltInType.BOOL;
    }

    @Override
    public void visitNewArray(Tree.NewArray expr, ScopeStack ctx) {
        expr.elemType.accept(this, ctx);
        expr.length.accept(this, ctx);
        var et = expr.elemType.type;
        var lt = expr.length.type;

        if (et.isVoidType()) {
            issue(new BadArrElementError(expr.elemType.pos));
            expr.type = BuiltInType.ERROR;
        } else {
            expr.type = new ArrayType(et);
        }

        if (lt.noError() && !lt.eq(BuiltInType.INT)) {
            issue(new BadNewArrayLength(expr.length.pos));
        }
    }

    @Override
    public void visitNewClass(Tree.NewClass expr, ScopeStack ctx) {
        var clazz = ctx.lookupClass(expr.clazz.name);
        if (clazz.isPresent()) {
            //抽象类不能进行实例化
            if(clazz.get().isAbstract())
                issue(new BadInstantiateError(expr.pos, expr.clazz.name));
            expr.symbol = clazz.get();
            expr.type = expr.symbol.type;
        } else {
            issue(new ClassNotFoundError(expr.pos, expr.clazz.name));
            expr.type = BuiltInType.ERROR;
        }
    }

    @Override
    public void visitThis(Tree.This expr, ScopeStack ctx) {
        if (ctx.currentMethod().isStatic()) {
            issue(new ThisInStaticFuncError(expr.pos));
        }
        expr.type = ctx.currentClass().type;
    }

    private boolean allowClassNameVar = false;

    @Override
    public void visitVarSel(Tree.VarSel expr, ScopeStack ctx) {
        if (expr.receiver.isEmpty()) {
            // Variable, which should be complicated since a legal variable could refer to
            // a local var, a visible member var, and a class name.
            var symbol = ctx.lookupBefore(expr.name, localVarDefPos.orElse(expr.pos));
            if (symbol.isPresent()) {
                if(!varListStack.contains(expr.name)){
                    if (symbol.get().isVarSymbol()) {
                        var var = (VarSymbol)symbol.get();  //改回varSymbol???
                        expr.symbol = var;
                        expr.type = var.type;
                        if (((VarSymbol)var).isMemberVar()) {
                            if (ctx.currentMethod().isStatic()) {
                                issue(new RefNonStaticError(expr.pos, ctx.currentMethod().name, expr.name));
                            } else {
                                expr.setThis();
                            }
                        }
                        return;
                    }
                    if (symbol.get().isClassSymbol() && allowClassNameVar) { // special case: a class name
                        var clazz = (ClassSymbol) symbol.get();
                        expr.type = clazz.type;
                        expr.isClassName = true;
                        return;
                    }
                    if(symbol.get().isMethodSymbol()){
                        var method = (MethodSymbol)symbol.get();
//                        expr.symbol =method;  //FLAG
                        expr.type = method.type;
                        if(method.isMemberMethod()){
                            expr.isMemberMethodName = true;
                            if(ctx.currentMethod().isStatic()&&!method.isStatic()){
                                issue(new RefNonStaticError(expr.pos, ctx.currentMethod().name,expr.name));
                            }else{
                                expr.setThis();
                            }
                        }
                        return;
                    }
                }
            }

            expr.type = BuiltInType.ERROR;
            issue(new UndeclVarError(expr.pos, expr.name));
            return;
        }

// has receiver
        var receiver = expr.receiver.get();
        allowClassNameVar = true;
        receiver.accept(this, ctx);
        allowClassNameVar = false;
        var rt = receiver.type;
        expr.type = BuiltInType.ERROR;

        if (!rt.noError()) {
            return;
        }

        if (rt.isArrayType() && expr.name.equals("length")) {
            expr.isArrayLength = true;
            expr.type = new FunType(BuiltInType.INT, new ArrayList<Type>());
            return;
        }
        if (!rt.isClassType()) {
            issue(new NotClassFieldError(expr.pos, expr.name, rt.toString()));
            return;
        }
        //receiver type is ClassType
        var ct = (ClassType) rt;
        var field = ctx.getClass(ct.name).scope.lookup(expr.name);
        //成员变量和非静态成员函数不允许通过类名访问
        if (receiver instanceof Tree.VarSel) {
            var v1 = (Tree.VarSel) receiver;
            if (v1.isClassName) {
                if(field.isPresent() && (field.get().isVarSymbol()||field.get().isMethodSymbol()&& !((MethodSymbol)field.get()).isStatic())){
                    issue(new NotClassFieldError(expr.pos, expr.name, ctx.getClass(v1.name).type.toString()));
                    return;
                }
            }
        }

        if (field.isPresent() && field.get().isVarSymbol()) {
            var var = (VarSymbol) field.get();
            if (var.isMemberVar()) {
                expr.symbol = var;
                expr.type = var.type;
                if (!ctx.currentClass().type.subtypeOf(var.getOwner().type)) {
                    // member vars are protected
                    issue(new FieldNotAccessError(expr.pos, expr.name, ct.toString()));
                }
            }
        }else if(field.isPresent() && field.get().isMethodSymbol()){
            var method = (MethodSymbol)field.get();
            if(method.isMemberMethod()){
                expr.isMemberMethodName = true;
//                expr.symbol = method;  //FLAG
                expr.type = method.type;
            }
            return;
        }
        else if (field.isEmpty()) {
            issue(new FieldNotFoundError(expr.pos, expr.name, ct.toString()));
        } else {

            issue(new NotClassFieldError(expr.pos, expr.name, ct.toString()));
        }
    }

    @Override
    public void visitIndexSel(Tree.IndexSel expr, ScopeStack ctx) {
        expr.array.accept(this, ctx);
        expr.index.accept(this, ctx);
        var at = expr.array.type;
        var it = expr.index.type;

        if(!at.noError()){
            expr.type=BuiltInType.ERROR;
            return;
        }
        if (!at.isArrayType()) {
            issue(new NotArrayError(expr.array.pos));
            expr.type = BuiltInType.ERROR;
            return;
        }

        expr.type = ((ArrayType) at).elementType;
        if (!it.eq(BuiltInType.INT)) {
            issue(new SubNotIntError(expr.pos));
        }
    }

    @Override
    public void visitCall(Tree.Call expr, ScopeStack ctx) {
        //求返回类型
        expr.func.accept(this, ctx);//访问expr
        if (expr.func.type.hasError()) {
            expr.type = BuiltInType.ERROR;
            return;
        }

        if (!expr.func.type.isFuncType()) {
            issue(new NotCallableError(expr.pos, expr.func.type.toString()));
            expr.type = BuiltInType.ERROR;
            return;
        }

        if (expr.func instanceof Tree.VarSel) {
            var v1 = (Tree.VarSel) expr.func;
            if (v1.isArrayLength) {
                expr.isArrayLength = true;
                expr.type = BuiltInType.INT;
                if (!expr.args.isEmpty())
                    issue(new BadLengthArgError(expr.pos, expr.args.size()));
                return;
            }
        }

        typeCall(expr, ctx);
    }

    private void typeCall(Tree.Call call, ScopeStack ctx) {

        call.type = ((FunType)(call.func.type)).returnType;

        //访问参数列表
        var args = call.args;
        for(var arg: args)
            arg.accept(this, ctx);

        var type=(FunType)call.func.type;
        if (type.arity() != args.size()) {

            issue(new BadArgCountError(call.pos, !(call.func instanceof Tree.VarSel) ? "" : ((Tree.VarSel)call.func).name, type.arity(), args.size(),!(call.func instanceof Tree.VarSel)));
        }
        var iter1 = type.argTypes.iterator();
        var iter2 = call.args.iterator();
        for (int i = 1; iter1.hasNext() && iter2.hasNext(); i++) {
            Type t1 = iter1.next();
            Tree.Expr e = iter2.next();
            Type t2 = e.type;
            if (t2.noError() && !t2.subtypeOf(t1)) {
                issue(new BadArgTypeError(e.pos, i, t2.toString(), t1.toString()));
            }
        }
    }

    @Override
    public void visitClassTest(Tree.ClassTest expr, ScopeStack ctx) {
        expr.obj.accept(this, ctx);
        expr.type = BuiltInType.BOOL;

        if (!expr.obj.type.isClassType()) {
            issue(new NotClassError(expr.obj.type.toString(), expr.pos));
        }
        var clazz = ctx.lookupClass(expr.is.name);
        if (clazz.isEmpty()) {
            issue(new ClassNotFoundError(expr.pos, expr.is.name));
        } else {
            expr.symbol = clazz.get();
        }
    }

    @Override
    public void visitClassCast(Tree.ClassCast expr, ScopeStack ctx) {
        expr.obj.accept(this, ctx);

        if (!expr.obj.type.isClassType()) {
            issue(new NotClassError(expr.obj.type.toString(), expr.pos));
        }

        var clazz = ctx.lookupClass(expr.to.name);
        if (clazz.isEmpty()) {
            issue(new ClassNotFoundError(expr.pos, expr.to.name));
            expr.type = BuiltInType.ERROR;
        } else {
            expr.symbol = clazz.get();
            expr.type = expr.symbol.type;
        }
    }

    @Override
    public void visitLocalVarDef(Tree.LocalVarDef stmt, ScopeStack ctx) {
        if (stmt.initVal.isEmpty()) return;
        varListStack.add(stmt.name);
        var initVal = stmt.initVal.get();

        localVarDefPos = Optional.ofNullable(stmt.id.pos);
        initVal.accept(this, ctx);
        localVarDefPos = Optional.empty();
        varListStack.remove(varListStack.size()-1);
        var lt = stmt.symbol.type;
        var rt = initVal.type;
        //var类型推导
        if(lt == null){
            if(rt.isVoidType()){
                issue(new BadVarTypeError(stmt.id.pos,stmt.id.name));
                stmt.symbol.type=BuiltInType.ERROR;
                return;
            }
            else
                stmt.symbol.type = rt;
        }else{
            if (lt.noError() && (!rt.subtypeOf(lt))) {
                issue(new IncompatBinOpError(stmt.assignPos, lt.toString(), "=", rt.toString()));
            }
        }
    }

    @Override
    public void visitLambda(Tree.Lambda lambda, ScopeStack ctx){
        if(lambda.expr != null){
            ctx.open(lambda.symbol.lambdaScope);
            ctx.open(lambda.symbol.localScope);
            lambda.expr.accept(this, ctx);
            ctx.close();
            ctx.close();
            ((FunType)lambda.symbol.type).returnType = lambda.expr.type;
        }
        else if(lambda.body != null){
            ctx.open(lambda.symbol.lambdaScope);
            typeListStack.add(new ArrayList<Type>());
            lambda.body.accept(this,ctx);
            Type returnType = getReturnType(lambda.body);
            typeListStack.remove(typeListStack.size()-1);
            ctx.close();
            ((FunType)lambda.symbol.type).returnType = returnType;
            ((FunType)lambda.type).returnType = returnType;
        }

    }

    // Only usage: check if an initializer cyclically refers to the declared variable, e.g. var x = x + 1
    private Optional<Pos> localVarDefPos = Optional.empty();

    private Type getReturnType(Tree.Block block){
        if(typeListStack.get(typeListStack.size()-1).isEmpty()){
            return BuiltInType.VOID;
        }
        else{
            if(!block.isClose){
                for(var type: typeListStack.get(typeListStack.size()-1)){
                    if(!type.eq(BuiltInType.VOID)){
                        issue(new MissingReturnError(block.pos));
                        break;
                    }
                }
            }
            Type returnType = upperBound(typeListStack.get(typeListStack.size()-1));
            if(returnType.eq(BuiltInType.ERROR))
                issue(new IncompatRetTypeError(block.pos));
            return returnType;
        }
    }

    private Type upperBound(List<Type> list){
        Type typek = null;
        for(var type: list){
            if(!type.eq(BuiltInType.NULL)){
                typek = type;
                break;
            }
        }

        if(typek!=null && (typek.isBaseType()||typek.isVoidType() || typek.isArrayType())){
            for(var type: list) {
                if (!type.eq(typek))
                    return BuiltInType.ERROR;
            }
            return typek;
        }
        else if(typek!=null && typek.isClassType()){
            for(var type: list){
                if(!type.subtypeOf(typek)){
                    boolean findSuper = false;
                    while(!((((ClassType)typek).superType).isEmpty())){
                        typek = (((ClassType)typek).superType).get();
                        if(type.subtypeOf(typek))
                            findSuper = true;
                            break;
                    }
                    if(!findSuper)
                        return BuiltInType.ERROR;
                }
            }
            return typek;
        }
        else if(typek!=null && typek.isFuncType()){
            var retList=new ArrayList<Type>();
            var argList=new ArrayList<ArrayList<Type>>();
            int argsCount = ((FunType)typek).arity();

            for(int i=0;i<argsCount;i++){
                argList.add(new ArrayList<Type>());
            }
            for(var type: list){
                if(!type.isFuncType()||!(((FunType)type).arity()==argsCount)) {
                    return BuiltInType.ERROR;
                }
                else{
                    retList.add(((FunType)type).returnType);
                    for(int i=0;i<argsCount;i++) {
                        argList.get(i).add(((FunType)type).argTypes.get(i));
                    }
                }
            }
            Type rt = upperBound(retList);
            if(rt.eq(BuiltInType.ERROR)) {
                return BuiltInType.ERROR;
            }
            var argst = new ArrayList<Type>();
            for(var argList_i:argList){
                Type argt = lowerBound(argList_i);
                if(argt.eq(BuiltInType.ERROR))
                    return BuiltInType.ERROR;
                argst.add(argt);
            }
            return new FunType(rt,argst);
        }
        else if(typek == null)
            return BuiltInType.NULL;
        else
            return BuiltInType.ERROR;
    }

    private Type lowerBound(List<Type> list) {
        Type typek = null;

        for (var type : list) {
            if (!type.eq(BuiltInType.NULL)) {
                typek = type;
                break;
            }
        }
        if (typek != null && (typek.isBaseType() || typek.isVoidType() || typek.isArrayType())) {
            for (var type : list) {
                if (!type.eq(typek))
                    return BuiltInType.ERROR;
            }
            return typek;
        } else if (typek != null && typek.isClassType()) {
            for (var type : list) {
                if (type.subtypeOf(typek)) {
                    typek = type;
                } else if (!typek.subtypeOf(type)) {
                    return BuiltInType.ERROR;
                }
            }
            return typek;
        } else if (typek != null && typek.isFuncType()) {
            var retList = new ArrayList<Type>();
            var argList = new ArrayList<ArrayList<Type>>();
            int argsCount = ((FunType) typek).arity();

            for (int i = 0; i < argsCount; i++) {
                argList.add(new ArrayList<Type>());
            }
            for (var type : list) {
                if (!type.isFuncType() || !(((FunType) type).arity() == argsCount)) {
                    return BuiltInType.ERROR;
                } else {
                    retList.add(((FunType) type).returnType);
                    for (int i = 0; i < argsCount; i++) {
                        argList.get(i).add(((FunType) type).argTypes.get(i));
                    }
                }
            }

            Type rt = lowerBound(retList);
            if(rt.eq(BuiltInType.ERROR)) {
                return BuiltInType.ERROR;
            }
            var argst = new ArrayList<Type>();
            for(var argList_i:argList){
                Type argt = upperBound(argList_i);
                if(argt.eq(BuiltInType.ERROR))
                    return BuiltInType.ERROR;
                argst.add(argt);
            }
            return new FunType(rt,argst);
        }else if(typek == null)
            return BuiltInType.NULL;
        return BuiltInType.ERROR;
    }

    private  List<List<Type>> typeListStack = new ArrayList<List<Type>>();

    private List<String> varListStack = new ArrayList<String>();
}
