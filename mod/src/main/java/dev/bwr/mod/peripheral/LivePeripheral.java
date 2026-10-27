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
    private final boolean machine;
    /** The four methods every machine peripheral has besides its own (the panel is not a machine). */
    private static final String[] MACHINE_METHODS={"getName","setName","getPort","setPort"};
    private static final Map<Class<?>,Method[]> SCHEMAS=new java.util.concurrent.ConcurrentHashMap<>();
    public LivePeripheral(Level level,BlockPos anchor,Block block,String type,Class<? extends IPeripheral> schema,Supplier<IPeripheral> resolve) {
        this.level=level;this.anchor=anchor.immutable();this.block=block;this.type=type;this.resolve=resolve;
        methods=SCHEMAS.computeIfAbsent(schema,c->Arrays.stream(c.getMethods()).filter(m->m.isAnnotationPresent(LuaFunction.class))
                .sorted(Comparator.comparing(Method::getName)).toArray(Method[]::new));
        machine=!PanelPeripheral.TYPE.equals(type);
        String[] own=Arrays.stream(methods).map(Method::getName).toArray(String[]::new);
        if(machine){
            names=Arrays.copyOf(own,own.length+MACHINE_METHODS.length);
            System.arraycopy(MACHINE_METHODS,0,names,own.length,MACHINE_METHODS.length);
        }else names=own;
    }
    /** The level this face is in. */
    public Level level(){return level;}
    /** The block this face is on (a machine cell; {@code DeviceResolver} finds the machine from it). */
    public BlockPos anchor(){return anchor;}
    /** Whether this is a plant machine (every type but the panel). */
    public boolean isMachine(){return machine;}
    @Override public String getType(){return type;}
    /** A panel also answers to the plant computer's type, so a program can find it with {@code peripheral.find}. */
    @Override public java.util.Set<String> getAdditionalTypes(){return PanelPeripheral.TYPE.equals(type)?java.util.Set.of(PanelPeripheral.PLANT_TYPE):java.util.Set.of();}
    @Override public Object getTarget(){return this;}
    @Override public String[] getMethodNames(){return names.clone();}
    @Override public boolean equals(IPeripheral other){return other instanceof LivePeripheral p&&p.level==level&&p.anchor.equals(anchor)&&p.block==block&&p.type.equals(type);}
    @Override public MethodResult callMethod(IComputerAccess computer,ILuaContext context,int index,IArguments args)throws LuaException {
        if(machine&&index>=methods.length&&index<methods.length+MACHINE_METHODS.length)return machineMethod(index-methods.length,context,args);
        if(index<0||index>=methods.length)throw new LuaException("Unknown machine method");
        Method method=methods[index];Class<?>[] types=method.getParameterTypes();Object[] converted=new Object[types.length];
        for(int i=0;i<types.length;i++) {
            if(types[i]==double.class)converted[i]=args.getDouble(i);
            else if(types[i]==int.class)converted[i]=args.getInt(i);
            else if(types[i]==boolean.class)converted[i]=args.getBoolean(i);
            else if(types[i]==String.class)converted[i]=args.getString(i);
            else if(types[i]==String[].class)converted[i]=tableStrings(args.getTableUnsafe(i));
            else if(types[i]==Map.class&&method.getDeclaringClass()==GridTiePeripheral.class&&method.getName().equals("setMeters"))converted[i]=meterPositions(args.getTableUnsafe(i));
            else if(types[i]==Map.class&&method.getDeclaringClass()==PanelPeripheral.class)converted[i]=plainTable(args.getTable(i));
            else if(types[i]==Object.class&&method.getDeclaringClass()==PanelPeripheral.class)converted[i]=plainValue(args.get(i));
            else if(types[i]==java.util.Optional.class&&optionalOf(method,i)==Integer.class)converted[i]=args.optInt(i);
            else if(types[i]==java.util.Optional.class&&optionalOf(method,i)==Double.class)converted[i]=args.optDouble(i);
            else throw new LuaException("Unsupported machine method argument");
        }
        // Resolve after scheduling, not while still on the computer thread.
        return context.executeMainThreadTask(()->invoke(method,converted));
    }
    /**
     * {@code getName()}, {@code setName(text)}, {@code getPort()} and {@code setPort(n)} on every machine: its equipment
     * tag and its CC port (0 to 9999; nil when none). A name or a port is only ever set by the program's own call; nothing
     * here names or numbers machines in bulk.
     */
    /** The type an {@code Optional} parameter holds ({@code Optional<Integer>}: an optional whole-number argument), or null. */
    private static java.lang.reflect.Type optionalOf(Method method,int i){
        return method.getGenericParameterTypes()[i] instanceof java.lang.reflect.ParameterizedType p?p.getActualTypeArguments()[0]:null;
    }
    private MethodResult machineMethod(int which,ILuaContext context,IArguments args)throws LuaException {
        String text=which==1?args.getString(0):null;
        int port=which==3?args.getInt(0):0;
        if(which==3&&!dev.bwr.mod.panel.MachinePorts.valid(port))throw new LuaException("Port must be 0 to "+dev.bwr.mod.panel.MachinePorts.MAX_PORT);
        return context.executeMainThreadTask(()->{
            if(level.getServer()==null||!level.getServer().isSameThread())throw new LuaException("Machine access requires server thread");
            if(!level.isLoaded(anchor)||!level.getBlockState(anchor).is(block))throw new LuaException("Machine connection removed or unloaded");
            var owner=dev.bwr.mod.signal.DeviceResolver.resolve(level,anchor);
            if(owner==null||dev.bwr.mod.signal.SignalProviders.of(owner)==null)throw new LuaException("Machine controller unavailable; assemble and load the machine");
            switch(which){
                case 0:return new Object[]{dev.bwr.mod.panel.MachineNames.name(owner)};
                case 1:dev.bwr.mod.panel.MachineNames.setName(owner,text);return new Object[]{dev.bwr.mod.panel.MachineNames.name(owner)};
                case 2:{int current=dev.bwr.mod.panel.MachinePorts.get(owner);return new Object[]{current==dev.bwr.mod.panel.MachinePorts.NONE?null:(Object)current};}
                default:dev.bwr.mod.panel.MachinePorts.set(owner,port);return new Object[]{port};
            }
        });
    }
    /** Most keys a table handed to a panel call may have. */
    private static final int MAX_TABLE_KEYS=32;
    /** A Lua table of named settings (a plant computer element) copied to plain Java: string keys; number, string or boolean values. */
    public static Map<String,Object> plainTable(Map<?,?> table)throws LuaException {
        if(table.size()>MAX_TABLE_KEYS)throw new LuaException("Table has too many entries (limit "+MAX_TABLE_KEYS+")");
        var copy=new LinkedHashMap<String,Object>();
        for(var entry:table.entrySet()){
            if(!(entry.getKey() instanceof String key))throw new LuaException("Table keys must be strings");
            copy.put(key,plainValue(entry.getValue()));
        }
        return copy;
    }
    /** A single Lua value for a panel call: nil, a number, a string or a boolean. */
    public static Object plainValue(Object value)throws LuaException {
        if(value==null||value instanceof Boolean||value instanceof String)return value;
        if(value instanceof Number n)return n.doubleValue();
        throw new LuaException("Expected nil, a number, a string or a boolean");
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
