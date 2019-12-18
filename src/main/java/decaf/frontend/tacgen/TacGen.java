package decaf.frontend.tacgen;

import decaf.driver.Config;
import decaf.driver.Phase;
import decaf.frontend.tree.Tree;
import decaf.lowlevel.label.FuncLabel;
import decaf.lowlevel.tac.*;

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Optional;

/**
 * The tacgen phase: translate an abstract syntax tree to TAC IR.
 */
public class TacGen extends Phase<Tree.TopLevel, TacProg> implements TacEmitter {

    public TacGen(Config config) {
        super("tacgen", config);
    }

    @Override
    public TacProg transform(Tree.TopLevel tree) {
        // Create class info.
        var info = new ArrayList<ClassInfo>();
        for (var clazz : tree.classes) {
            info.add(clazz.symbol.getInfo());
        }
//        System.out.println(tree.lambdas.toString());

        var pw = new ProgramWriter(info,tree.lambdas);

        // Step 1: create virtual tables.
        pw.visitVTables();

        // Step 2: emit tac instructions for every method.
        for (var clazz : tree.classes) {
            for (var method : clazz.methods()) {
                FuncVisitor mv;
                if (method.symbol.isMain()) {
                    mv = pw.visitMainMethod();
                } else {
                    var numArgs = method.params.size();
                    var i = 0;
                    if (!method.isStatic()) {
                        numArgs++;
                        i++;
                    }
                    mv = pw.visitFunc(clazz.name, method.name, numArgs);
                    for (var param : method.params) {
                        param.symbol.temp = mv.getArgTemp(i);
                        i++;
                    }
                }
                if(!method.isAbstract())
                    method.body.accept(this, mv);
                mv.visitEnd();
            }
        }

        // emit tac instructions for every lambda.
        for (var lambda : tree.lambdas) {
            FuncVisitor mv;
            // Remember calling convention: pass `this` (if non-static) as an extra argument, via reversed temps.
            var numArgs = lambda.params.size()+lambda.capture.size()+1;
            mv = pw.visitFunc("Lambda_orz", "lambda"+lambda.pos, numArgs);
            var i = 1;
            for(var capturevar : lambda.capture){
                capturevar.temp = mv.getArgTemp(i);
                i++;
            }
            for (var param : lambda.params) {
                param.symbol.temp = mv.getArgTemp(i);
                i++;
            }

            if (lambda.expr != null) {
                lambda.expr.accept(this, mv);
                mv.visitReturn(lambda.expr.val);
            }
            if (lambda.body != null) {
                lambda.body.accept(this, mv);
            }
            mv.visitEnd();
        }

        return pw.visitEnd();
    }

    @Override
    public void onSucceed(TacProg program) {
        if (config.target.equals(Config.Target.PA3)) {
            // First dump the tac program to file,
            var path = config.dstPath.resolve(config.getSourceBaseName() + ".tac");
            try {
                var printer = new PrintWriter(path.toFile());
                program.printTo(printer);
                printer.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }

            // and then execute it using our simulator.
            var simulator = new Simulator(System.in, config.output);
            simulator.execute(program);
        }
    }
}
