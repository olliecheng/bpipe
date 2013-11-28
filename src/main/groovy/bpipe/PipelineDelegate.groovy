package bpipe

import groovy.util.logging.Log;

/**
 * Forwards all calls through to a thread specific context.
 * 
 * @author simon.sadedin@mcri.edu.au
 */
@Log
class PipelineDelegate {
    
    PipelineDelegate(PipelineContext ctx) {
        if(ctx == null)
            throw new IllegalArgumentException("Attempt to set null pipeline context")
        this.context.set(ctx)
    }
    
    ThreadLocal<PipelineContext> context = new ThreadLocal<PipelineContext>() {
        void set(PipelineContext c) {
            super.set(c)
        }
    }
    
    static void setDelegateOn(PipelineContext context, Closure body) {
        
        // Note we must synchronize on the inner body, not outer as 
        // otherwise can result in the same stage getting a delegate set
        // in parallel (race). This causes NullPointer exception later on with null ctx.
        def realBody = body
        if(body instanceof ParameterizedClosure) {
            ParameterizedClosure pc = body
            // Note: not using direct access (ie: with @) causes property not found here.
            // Not at all sure why, but presumably all the messing around with delegates is to blame
            realBody = pc.@body
        }
        
        synchronized(realBody) {
            // log.fine "Setting or creating a delegate for context ${context.hashCode()} body ${body.hashCode()}/${body.delegate.class.name} in thread ${Thread.currentThread().id}"
            if(body instanceof ParameterizedClosure) {
                ParameterizedClosure pc = body
                context.localVariables = pc.getExtraVariables()
            }
        
            if(body.getDelegate() == null || !(body.getDelegate() instanceof PipelineDelegate)) {
                
                log.fine "Existing delegate has type ${body.delegate.class.name} in thread ${Thread.currentThread().id}"
        
                def d = new PipelineDelegate(context)
                body.setDelegate(d)
            }
            else {
                body.getDelegate().context.set(context)
            }
            
            context.myDelegate =body.getDelegate()
        }
    }
    
    def methodMissing(String name, args) {
        log.fine "Query for method $name on ${context.get()} via delegate ${this} in thread ${Thread.currentThread().id}"
        
        if(name == "from") {
            if(args.size()<1) 
                throw new IllegalArgumentException("from requires an argument: please supply a pattern or file extension that from should match on")
                
            def actualArgs 
            Closure body = null
            if(args[-1] instanceof Closure) {
                actualArgs = args[0..-2] as List
                body = args[-1]
            }
            else
                actualArgs = args[0..-1]
                
            if(actualArgs[0] instanceof List && actualArgs.size()==1)
                actualArgs = actualArgs[0]
                
            context.get().invokeMethod("fromImpl", [actualArgs, body] as Object[])
        }
        else
        if(name in ["produce","transform","filter"]) {
            if(name == "transform" && !(args[-1] instanceof Closure)) {
                args = args.clone() + [null]
            }
            else
            if(args.size()<2)
                throw new IllegalArgumentException("$name requires an argument: please supply a file name or wild card expression matching files to be produced")
                
            def actualArgs = args[0..-2] as List
            def body = args[-1]
            
            context.get().invokeMethod(name+"Impl", [actualArgs, body] as Object[])
        }
        else
        if(name == "multi") {
            context.get().invokeMethod("multiExec", [args as List] as Object[])
        }
        else {
            context.get().myDelegate = null
            try {
                return context.get().invokeMethod(name, args)
            }
            finally {
                context.get().myDelegate = this
            }
        }
    }
    
    def propertyMissing(String name) {
        
        // The context can be put into a mode where missing variables should echo themselves back
        // ie.  $foo will produce value $foo.  This is used to preserve $'d values in 
        // R scripts while still allowing interpretation of the ones we want ($input, $output, etc)
        
        log.fine "Query for $name on ${context.get()} via delegate ${this} in thread ${Thread.currentThread().id}"
        
        PipelineContext ctx = context.get()
        
        if(ctx.hasProperty(name)) {
            return ctx.getProperty(name)
        }
        else
        if(Pipeline.currentRuntimePipeline.get()?.variables?.containsKey(name)) {
            return Pipeline.currentRuntimePipeline.get().variables.get(name)
        }
        else
        if(ctx.localVariables.containsKey(name)) {
            return ctx.localVariables[name]
        }
        else
        if(ctx.branch.properties.containsKey(name)) {
            return ctx.branch.getProperty(name)
        } 
        else
        if(name.matches("output[0-9]{1,}\$")) {
            // extract output number
            int n = (name =~ "output([0-9]{1,})\$")[0][1].toInteger()
            log.info "Output $n reference resolved by PipelineDelegate"
            return context.get().invokeMethod("getOutputByIndex", [n-1] as Object[])
        }
        else
        if(name.matches("input[0-9]{1,}\$")) {
            // extract input number
            int n = (name =~ "input([0-9]{1,})\$")[0][1].toInteger()
            log.info "Input $n reference resolved by PipelineDelegate"
            return context.get().invokeMethod("getInputByIndex", [n-1] as Object[])
        }
        else
        if(name == "region") {
            def genome = Pipeline.genomes.values()[0]
            if(!genome) 
                throw new PipelineError("You referenced a region without defining a genome first. Please specify a genome, eg, using:\n\n\tgenome \"hg19\"\n")
                
            // Construct the regions in Samtools format
            // We filter out "unusual" chromosomes by default
            String regionValue = genome.sequences.values().grep { !it.name.contains('_') }
                                                           .collect { it.name + ':' +it.range.from + '-' +it.range.to }
                                                           .join(" ")
            ctx.localVariables["region"] = 
                new RegionValue(value:regionValue)
            
            return ctx.localVariables.region
        }
        else
        if(name != "input" && ctx.echoWhenNotFound) {
            try {
                def result = ctx.getProperty(name)
                //log.info "Retrieved result for $name = $result"
                return result
            }
            catch(Throwable t) {
                return '$'+name
            }
        }
    }
}