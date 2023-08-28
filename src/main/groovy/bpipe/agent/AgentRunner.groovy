package bpipe.agent

import bpipe.BpipeLogFormatter

import java.util.concurrent.Semaphore
import java.util.logging.FileHandler
import bpipe.Config
import groovy.util.logging.Log;

@Log
class AgentRunner {
    static void main(List<String> args) {
        
        CliBuilder cli = new CliBuilder(usage: "bpipe agent [-v] [-n <concurrency>")
        cli.with {
            v 'Verbose mode'
            n 'How many concurrent jobs to run', args:1, required:false
            s 'Wait for a single command and then exit (useful for debugging)', longOpt: 'single'
            t 'Set timeout to exit after if no commands received', longOpt: 'timeout', args: 1, type: Long
        }
        
        def opts = cli.parse(args)
        
        println "=" * Config.config.columns
        println "Starting Bpipe agent ..."
        println "=" * Config.config.columns
        
        initializeLogging()
        
        if(opts.v) 
            bpipe.Utils.configureVerboseLogging()
       
        Config.readUserConfig()
        
        bpipe.agent.Agent agent 
        if(Config.userConfig.containsKey('agent')) {
            agent = new JMSAgent(Config.userConfig.agent)
        }
        else {
            agent = new bpipe.agent.HttpAgent()
        }
        
        if(opts.n) {
            log.info("Setting concurrency = " + opts.n)
            agent.concurrency = new Semaphore(opts.n.toInteger())
        }
        
        if(opts.s)
            agent.singleShot = true
            
        log.info "Timeout option = " + opts.t
        if(opts.t) {
            log.info "Scheduling timeout in $opts.t ms"
            new Thread({
                Thread.sleep(opts.t)
                if(agent.executed == 0) {
                    log.info "Timeout of $opts.t ms expired with no jobs executed: exiting"
                    agent.stopRequested = true
                }
                else  {
                    log.info "Timeout of $opts.t ms expired with ${agent.executed} jobs executed: not exiting"
                }
            }).start()
        }

        agent.run()
        
    }
    
    

    private static initializeLogging() {
        def parentLog = log.getParent()
        parentLog.getHandlers().each { parentLog.removeHandler(it) }

        File logFile = new File(System.getProperty("user.home")+"/.bpipedb/agent.log")
        if(logFile.exists()) {
            logFile.delete()
        }

        // The current log file
        FileHandler fh = new FileHandler(logFile.path)
        fh.setFormatter(new BpipeLogFormatter())
        parentLog.addHandler(fh)
    }
    
}
