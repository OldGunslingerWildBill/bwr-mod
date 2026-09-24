package dev.bwr.mod.devtest;

import dan200.computercraft.api.lua.*;
import dan200.computercraft.api.peripheral.*;
import net.minecraft.server.MinecraftServer;
import java.util.Arrays;
import java.util.concurrent.*;

/** Exercises the same IDynamicPeripheral entry point a computer calls. Dev-only. */
public final class PeripheralTestCalls {
    private PeripheralTestCalls() {}
    public static Object[] call(Object peripheral,String name,Object... args)throws LuaException {
        return call(null,peripheral,name,args);
    }
    public static Object[] call(MinecraftServer server,Object peripheral,String name,Object... args)throws LuaException {
        var p=(IDynamicPeripheral)peripheral;
        int index=Arrays.asList(p.getMethodNames()).indexOf(name);
        if(index<0)throw new AssertionError("Missing Lua method: "+name);
        ILuaContext context=new ILuaContext() {
            @Override public long issueMainThreadTask(LuaTask task){throw new AssertionError("unexpected issueMainThreadTask");}
            @Override public MethodResult executeMainThreadTask(LuaTask task)throws LuaException {
                if(server==null||server.isSameThread())return MethodResult.of(task.execute());
                var future=new CompletableFuture<Object[]>();
                server.execute(()->{try{future.complete(task.execute());}catch(Throwable ex){future.completeExceptionally(ex);}});
                try{return MethodResult.of(future.get(10,TimeUnit.SECONDS));}
                catch(ExecutionException ex){if(ex.getCause() instanceof LuaException lua)throw lua;throw new AssertionError(ex.getCause());}
                catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AssertionError(ex);}
                catch(TimeoutException ex){throw new AssertionError("CC call did not return from the server thread",ex);}
            }
        };
        return p.callMethod(null,context,index,new ObjectArguments(args)).getResult();
    }
}
