package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.*;
import dan200.computercraft.api.peripheral.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Supplier;

/** A modem binds to a physical face, never to an instance from an unloaded controller chunk. */
public final class LivePeripheral implements IDynamicPeripheral {
    private final Level level;
    private final BlockPos anchor;
    private final Block block;
    private final String type;
    private final Supplier<IPeripheral> resolve;
    private final Method[] methods;
    private final String[] names;
    private static final Map<Class<?>,Method[]> SCHEMAS=new java.util.concurrent.ConcurrentHashMap<>();
    public LivePeripheral(Level level,BlockPos anchor,Block block,String type,Class<? extends IPeripheral> schema,Supplier<IPeripheral> resolve) {
        this.level=level;this.anchor=anchor.immutable();this.block=block;this.type=type;this.resolve=resolve;
        methods=SCHEMAS.computeIfAbsent(schema,c->Arrays.stream(c.getMethods()).filter(m->m.isAnnotationPresent(LuaFunction.class))
                .sorted(Comparator.comparing(Method::getName)).toArray(Method[]::new));
        names=Arrays.stream(methods).map(Method::getName).toArray(String[]::new);
    }
    @Override public String getType(){return type;}
    @Override public Object getTarget(){return this;}
    @Override public String[] getMethodNames(){return names.clone();}
    @Override public boolean equals(IPeripheral other){return other instanceof LivePeripheral p&&p.level==level&&p.anchor.equals(anchor)&&p.block==block&&p.type.equals(type);}
    @Override public MethodResult callMethod(IComputerAccess computer,ILuaContext context,int index,IArguments args)throws LuaException {
        if(index<0||index>=methods.length)throw new LuaException("Unknown machine method");
        Method method=methods[index];Class<?>[] types=method.getParameterTypes();Object[] converted=new Object[types.length];
        for(int i=0;i<types.length;i++) {
            if(types[i]==double.class)converted[i]=args.getDouble(i);
            else if(types[i]==int.class)converted[i]=args.getInt(i);
            else if(types[i]==boolean.class)converted[i]=args.getBoolean(i);
            else if(types[i]==String.class)converted[i]=args.getString(i);
            else throw new LuaException("Unsupported machine method argument");
        }
        // Resolve after scheduling, not while still on the computer thread.
        return context.executeMainThreadTask(()->invoke(method,converted));
    }
    private Object[] invoke(Method method,Object[] args)throws LuaException {
        if(level.getServer()==null||!level.getServer().isSameThread())throw new LuaException("Machine access requires server thread");
        if(!level.isLoaded(anchor)||!level.getBlockState(anchor).is(block))throw new LuaException("Machine connection removed or unloaded");
        IPeripheral current=resolve.get();
        if(current==null||!method.getDeclaringClass().isInstance(current))throw new LuaException("Machine controller unavailable; assemble and load the machine");
        try {
            Object value=method.invoke(current,args);
            return method.getReturnType()==void.class?new Object[0]:value instanceof Object[] values?values:new Object[]{value};
        } catch(InvocationTargetException e) {
            if(e.getCause() instanceof LuaException lua)throw lua;
            if(e.getCause() instanceof RuntimeException runtime)throw new LuaException("Machine call failed: "+runtime.getMessage());
            throw new LuaException("Machine call failed");
        } catch(ReflectiveOperationException e){throw new LuaException("Machine method unavailable");}
    }
}
