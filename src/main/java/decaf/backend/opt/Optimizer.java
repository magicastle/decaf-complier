package decaf.backend.opt;

import decaf.backend.dataflow.CFGBuilder;
import decaf.backend.dataflow.LivenessAnalyzer;
import decaf.driver.Config;
import decaf.driver.Phase;
import decaf.lowlevel.tac.Simulator;
import decaf.lowlevel.tac.TacProg;
import decaf.lowlevel.tac.TacInstr.DirectCall;
import decaf.lowlevel.tac.TacInstr.IndirectCall;

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Optional;

/**
 * TAC optimization phase: optimize a TAC program.
 * <p>
 * The original decaf compiler has NO optimization, thus, we implement the transformation as identity function.
 */
public class Optimizer extends Phase<TacProg, TacProg> {
    public Optimizer(Config config) {
        super("optimizer", config);
    }

    @Override
    public TacProg transform(TacProg input) {
        // for(var func: input.funcs){
        //     for(var instr : func.getInstrSeq()){
        //             System.out.println("instr:"+instr.toString()+"  "+instr.unused);
        //     }
        // }
        var analyzer = new LivenessAnalyzer<>();
        for(int optimizenum=0;optimizenum<10;optimizenum++){
            for(var func: input.funcs){
                var builder = new CFGBuilder<>();
                var cfg = builder.buildFrom(new ArrayList<>(func.getInstrSeq()));
                analyzer.accept(cfg);
    
                var size = func.getInstrSeq().size();
                var list = new ArrayList<>(func.getInstrSeq());
                for(int i=size-1;i>=0;i--){
                    var inst =list.get(i);
                    if(inst.unused){
                        if(inst instanceof IndirectCall){
                            var call = (IndirectCall)inst;
                            call.dst = Optional.empty();
                        }else if(inst instanceof DirectCall){
                            var call = (DirectCall)inst;
                            call.dst = Optional.empty();
                        }else{
                            func.getInstrSeq().remove(i);
                        }
                    }
                }
            }
        }
        return input;
    }

    @Override
    public void onSucceed(TacProg program) {
        if (config.target.equals(Config.Target.PA4)) {
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
