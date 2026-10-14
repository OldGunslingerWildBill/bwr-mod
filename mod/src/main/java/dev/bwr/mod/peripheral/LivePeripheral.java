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
            else if(types[i]==String[].class)converted[i]=tableStrings(args.getTableUnsafe(i));
            else if(types[i]==Map.class&&method.getDeclaringClass()==GridTiePeripheral.class&&method.getName().equals("setMeters"))converted[i]=meterPositions(args.getTableUnsafe(i));
            else throw new LuaException("Unsupported machine method argument");
        }
        // Resolve after scheduling, not while still on the computer thread.
        return context.executeMainThreadTask(()->invoke(method,converted));
    }
    /** A Lua array of strings (the rows of a panel blit), bounded so a script cannot hand over a huge table. */
    public static String[] tableStrings(LuaTable<?,?> table)throws LuaException {
        int count=table.length();
        if(count>MAX_TABLE_STRINGS)throw new LuaException("Table too long (limit "+MAX_TABLE_STRINGS+")");
        String[] values=new String[count];
        for(int i=0;i<count;i++)values[i]=table.getString(i+1);
        return values;
    }
    private static final int MAX_TABLE_STRINGS=256;
    /** Copy bounded coordinate values before scheduling; never retain a live Lua table. */
    private static Map<Integer,Map<String,Double>> meterPositions(LuaTable<?,?> table)throws LuaException {
        int count=table.length();if(count>32||table.size()!=count)throw new LuaException("Expected an array of at most 32 meter positions");
        var result=new LinkedHashMap<Integer,Map<String,Double>>();
        for(int i=1;i<=count;i++){
            Object row=table.get((double)i);if(row==null)row=table.get(i);
            if(!(row instanceof Map<?,?> values)||values.size()!=3)throw new LuaException("Expected x, y, z coordinates");
            var point=new LinkedHashMap<String,Double>();String[] names={"x","y","z"};
            for(int j=0;j<3;j++){
                Object value=values.get(names[j]);if(value==null)value=values.get((double)(j+1));if(value==null)value=values.get(j+1);
                if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=Math.rint(n.doubleValue())||n.doubleValue()<Integer.MIN_VALUE||n.doubleValue()>Integer.MAX_VALUE)
                    throw new LuaException("Meter coordinates must be finite integers");
                point.put(names[j],n.doubleValue());
            }
            result.put(i,Map.copyOf(point));
        }return Map.copyOf(result);
    }
    /** Panels need to know which computers are attached, to raise their input events. */
    @Override public void attach(IComputerAccess computer){
        if(PanelPeripheral.TYPE.equals(type))PanelPeripheral.attached(level,anchor,computer);
    }
    @Override public void detach(IComputerAccess computer){
        if(PanelPeripheral.TYPE.equals(type))PanelPeripheral.detached(computer);
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
