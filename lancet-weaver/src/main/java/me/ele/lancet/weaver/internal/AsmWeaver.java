package me.ele.lancet.weaver.internal;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import me.ele.lancet.base.api.ClassSupplier;
import me.ele.lancet.weaver.MetaParser;
import me.ele.lancet.weaver.Weaver;
import me.ele.lancet.weaver.internal.asm.CustomClassLoaderClassWriter;
import me.ele.lancet.weaver.internal.asm.classvisitor.CallClassVisitor;
import me.ele.lancet.weaver.internal.asm.classvisitor.ExcludeClassVisitor;
import me.ele.lancet.weaver.internal.asm.classvisitor.ExecuteClassVisitor;
import me.ele.lancet.weaver.internal.asm.classvisitor.TryCatchInfoClassVisitor;
import me.ele.lancet.weaver.internal.entity.CallInfo;
import me.ele.lancet.weaver.internal.entity.ExecuteInfo;
import me.ele.lancet.weaver.internal.entity.TotalInfo;
import me.ele.lancet.weaver.internal.entity.TryCatchInfo;
import me.ele.lancet.weaver.internal.meta.ClassMetaInfo;
import me.ele.lancet.weaver.internal.meta.MethodMetaInfo;
import me.ele.lancet.weaver.internal.parser.ReflectiveMetaParser;
import me.ele.lancet.weaver.internal.supplier.ComponentSupplier;
import me.ele.lancet.weaver.internal.supplier.DirCodeSupplier;
import me.ele.lancet.weaver.internal.supplier.FixedClassSupplier;


/**
 * Created by gengwanpeng on 17/3/21.
 */
public class AsmWeaver implements Weaver {


    public static AsmWeaver newInstance(Collection<File> jars, Collection<File> dirs) {
        URLClassLoader loader = URLClassLoader.newInstance(toUrls(jars, dirs), ClassLoader.getSystemClassLoader());

        ClassSupplier dirSupplier = new DirCodeSupplier(loader);
        ClassSupplier jarSupplier = new FixedClassSupplier(loader);
        ClassSupplier supplier = ComponentSupplier.newInstance(jarSupplier,dirSupplier);

        MetaParser parser = new ReflectiveMetaParser(loader);
        List<Class<?>> classes = supplier.get();
        List<ClassMetaInfo> list = parser.parse(supplier.get());

        return new AsmWeaver(loader, classes, list);
    }

    private final URLClassLoader loader;
    private final List<Class<?>> classes;
    private final TotalInfo totalInfo;
    private final Set<String> excludes;

    public AsmWeaver(URLClassLoader loader, List<Class<?>> classes, List<ClassMetaInfo> list) {
        this.loader = loader;
        this.classes = classes;
        this.totalInfo = convertToAopInfo(list);
        this.excludes = classes.stream().map(c -> c.getName().replace('.', '/')).collect(Collectors.toSet());
    }

    @Override
    public byte[] weave(byte[] input) {
        ClassReader cr = new ClassReader(input);

        CustomClassLoaderClassWriter cw = new CustomClassLoaderClassWriter(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.setCustomClassLoader(loader);

        CheckClassAdapter cc = new CheckClassAdapter(cw);

        ExecuteClassVisitor xcv = new ExecuteClassVisitor(Opcodes.ASM5, cw, totalInfo);
        CallClassVisitor ccv = new CallClassVisitor(Opcodes.ASM5, xcv, totalInfo);
        TryCatchInfoClassVisitor tcv = new TryCatchInfoClassVisitor(Opcodes.ASM5, ccv, totalInfo);
        ExcludeClassVisitor ecv = new ExcludeClassVisitor(Opcodes.ASM5, tcv, excludes);

        cr.accept(ecv, ClassReader.SKIP_FRAMES);

        if (ecv.isExclude()) {
            return input;
        }
        return cw.toByteArray();
    }

    private static TotalInfo convertToAopInfo(List<ClassMetaInfo> list) {
        List<ExecuteInfo> executeInfos = list.stream().flatMap(c -> c.infos.stream())
                .filter(m -> m.getType() == MethodMetaInfo.TYPE_EXECUTE)
                .map(m -> new ExecuteInfo(m.isStatic(), m.isMayCreateSuper(), m.getTargetClass(), m.getTargetSuperClass(), m.getTargetInterfaces(),
                        m.getTargetMethod(), m.getMyDescriptor(), m.getMyMethod(), m.getNode()))
                .collect(Collectors.toList());
        List<TryCatchInfo> tryCatchInfos = list.stream()
                .flatMap(c -> c.infos.stream())
                .filter(m -> m.getType() == MethodMetaInfo.TYPE_HANDLER)
                .map(m -> new TryCatchInfo(m.getRegex(), m.getMyClass(), m.getMyMethod(), m.getMyDescriptor()))
                .collect(Collectors.toList());

        List<CallInfo> callInfos = list.stream()
                .flatMap(c -> c.infos.stream())
                .filter(m -> m.getType() == MethodMetaInfo.TYPE_CALL)
                .map(m -> new CallInfo(m.isStatic(), m.getRegex(), m.getTargetClass(), m.getTargetMethod(), m.getMyDescriptor(), m.getMyClass(), m.getMyMethod(), m.getNode()))
                .peek(CallInfo::transformSelf)
                .collect(Collectors.toList());

        return new TotalInfo(executeInfos, tryCatchInfos, callInfos);
    }

    private static URL[] toUrls(Collection<File> jars, Collection<File> dirs) {
        List<File> list = new ArrayList<>(dirs);
        list.addAll(jars);
        return list.stream()
                .map(File::toURI)
                .map(u -> {
                    try {
                        return u.toURL();
                    } catch (MalformedURLException ignored) {
                        throw new AssertionError();
                    }
                })
                .toArray(URL[]::new);
    }
}
