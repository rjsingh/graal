package com.edinburgh.parallel.opencl;

import java.lang.reflect.*;
import java.util.*;

import org.jocl.*;

import com.edinburgh.parallel.opencl.phases.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.api.runtime.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.java.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.common.*;
import com.sun.xml.internal.ws.org.objectweb.asm.*;

public class ParallelUtil {

    public static void run(StructuredGraph sg, Object[] params) {
        long startTime = System.nanoTime();

        // Build Graph.
        GraalCodeCacheProvider runtime = Graal.getRequiredCapability(GraalCodeCacheProvider.class);
        GraphBuilderConfiguration config = GraphBuilderConfiguration.getEagerDefault();

        GraphBuilderPhase gbp = new GraphBuilderPhase(runtime, config, OptimisticOptimizations.NONE);
        gbp.apply(sg);

        RemoveValueProxyPhase valProxyPhase = new RemoveValueProxyPhase();
        valProxyPhase.apply(sg);

        // PHASE 2 - Transform arrays.

        // PHASE 2.1 collect array size information.
        BuildGPUArrayIndexDataPhase arrayPhase1 = new BuildGPUArrayIndexDataPhase(params);
        arrayPhase1.apply(sg);
        Map<LocalNode, Integer[]> indexData = arrayPhase1.getIndexData();

        // PHASE 2.2 build array node to array dimension mapping.
        ArrayDepthBuilderPhase arrayPhase2 = new ArrayDepthBuilderPhase();
        arrayPhase2.apply(sg);
        Map<Node, ArrayDepth> arrayAccessDim = arrayPhase2.getDepthInfo();

        // PHASE 2.3 add array dimension information for local nodes.
        ArrayDimensionPhase arrayPhase3 = new ArrayDimensionPhase();
        arrayPhase3.apply(sg);
        Map<Node, Integer> arrayDimensions = arrayPhase3.getDimensions();

        // PHASE 3 - Generate Code.

        // Phase 3.1 - Generate OpenCL kernel.
        String gpuCode = "";
        String methodName = sg.method().getName();
        String kernelName = methodName + "Kernel";

        // check if method has already been converted to opencl if yes then use that.
        if (!ParallelMethods.methodCodes.containsKey(methodName)) {
            SymbolTable table = new SymbolTable(indexData, arrayAccessDim, arrayDimensions);
            OpenCLCodeGen cgen = new OpenCLCodeGen(ParallelOptions.EnableComments.getValue(), table);
            cgen.beginCodeGen(sg.start(), sg);
            gpuCode = cgen.endCodeGen();
            kernelName = cgen.getKernelName();
            ParallelMethods.methodCodes.put(methodName, gpuCode);
        } else {
            gpuCode = ParallelMethods.methodCodes.get(methodName);
        }

        long endTime = System.nanoTime();
        System.out.println("Kernel Generation Time: " + (endTime - startTime));

        if (ParallelOptions.PrintCode.getValue()) {
            System.out.println(gpuCode);
        }

        startTime = System.nanoTime();
        if (ParallelOptions.Execute.getValue()) {

            long marshallStartTime = System.nanoTime();

            // Phase 3.2 - Transform parameters to OpenCL types for passing as parameters.
            List<OpenCLParameter> clParams = new ArrayList<>();
            for (Object param : params) {
                OpenCLParameter clParam = getOpenCLParameter(param, false);
                clParams.add(clParam);
            }

            // Also pass in the index data for each array as an OpenCL Parameter.
            for (LocalNode ln : sg.getNodes(LocalNode.class)) {
                if (!ln.kind().isPrimitive() && ln.objectStamp().type().isArray()) {
                    Integer[] arrayIndexData = indexData.get(ln);
                    OpenCLParameter clParam = getOpenCLParameter(arrayIndexData, true);
                    clParams.add(clParam);
                }
            }

            long marshallEndTime = System.nanoTime();

            System.out.println("Marshalling Time: " + (marshallEndTime - marshallStartTime));

            // Run code on GPU
            execute(kernelName, gpuCode, clParams);

            long unmarshallStartTime = System.nanoTime();

            // Rebuild original programmer parameters.
            rebuildParameters(clParams);

            long unmarshallEndTime = System.nanoTime();

            System.out.println("UnMarshall Time: " + (unmarshallEndTime - unmarshallStartTime));
        }
        endTime = System.nanoTime();

        System.out.println("Overal OpenCL Execution Time: " + (endTime - startTime));
    }

    public static void rebuildParameters(List<OpenCLParameter> clParams) {
        for (OpenCLParameter clParam : clParams) {
            if (clParam.isArray() && !clParam.isReadOnly()) {
                ArrayUtil.rebuild(clParam.getFlatArray(), clParam.getJavaParam(), new int[]{0});
            }
        }
    }

    public static OpenCLParameter getOpenCLParameter(Object param, boolean readOnly) {
        // find type.
        int typeSize = TypeUtil.getTypeSizeForCL(param);

        boolean isArray = false;
        int len = 0;
        Object newArray = null;
        if (param != null) {
            if (param.getClass().isArray()) {
                isArray = true;
                // flatten array.
                newArray = ArrayUtil.transformArray(param);
                len = Array.getLength(newArray);
            } else if (TypeUtil.isPrimative(param)) {
                // should be primative type but will come in as auto-boxed version e.g. if you
                // do param.getClass() you could get java.lang.Integer instead of I
                param = TypeUtil.getPrimativeForCL(param);
                readOnly = true;
                len = 1;
                newArray = param;
            }
        }

        // create pointer to object
        Pointer pToObj = getPointer(newArray);
        OpenCLParameter clParam = new OpenCLParameter(param, newArray, pToObj, typeSize, isArray, readOnly, len);
        return clParam;
    }

    public static Pointer getPointer(Object obj) {
        Method m;
        try {
            m = Pointer.class.getMethod("to", new Class[]{obj.getClass()});
            return (Pointer) m.invoke(null, obj);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void execute(String kernelName, String kernelSource, List<OpenCLParameter> clParams) {
        // The platform, device type and device number
        // that will be used
        final int platformIndex = 0;
        final long deviceType = ParallelOptions.UseCPU.getValue() ? CL.CL_DEVICE_TYPE_CPU : CL.CL_DEVICE_TYPE_GPU;
        final int deviceIndex = 0;

        // Enable exceptions and subsequently omit error checks in this sample
        CL.setExceptionsEnabled(true);

        // Obtain the number of platforms
        int numPlatformsArray[] = new int[1];
        CL.clGetPlatformIDs(0, null, numPlatformsArray);
        int numPlatforms = numPlatformsArray[0];

        // Obtain a platform ID
        cl_platform_id platforms[] = new cl_platform_id[numPlatforms];
        CL.clGetPlatformIDs(platforms.length, platforms, null);
        cl_platform_id platform = platforms[platformIndex];

        // Initialize the context properties
        cl_context_properties contextProperties = new cl_context_properties();
        contextProperties.addProperty(CL.CL_CONTEXT_PLATFORM, platform);

        // Obtain the number of devices for the platform
        int numDevicesArray[] = new int[1];
        CL.clGetDeviceIDs(platform, deviceType, 0, null, numDevicesArray);
        int numDevices = numDevicesArray[0];

        // Obtain a device ID
        cl_device_id devices[] = new cl_device_id[numDevices];
        CL.clGetDeviceIDs(platform, deviceType, numDevices, devices, null);
        cl_device_id device = devices[deviceIndex];

        // Create a context for the selected device
        cl_context context = CL.clCreateContext(contextProperties, 1, new cl_device_id[]{device}, null, null, null);

        // Create a command-queue for the selected device
        cl_command_queue commandQueue = CL.clCreateCommandQueue(context, device, CL.CL_QUEUE_PROFILING_ENABLE, null);

        // Create the program from the source code
        cl_program program = CL.clCreateProgramWithSource(context, 1, new String[]{kernelSource}, null, null);

        // Build the program
        long startTime = System.nanoTime();
        CL.clBuildProgram(program, 0, null, "-cl-opt-enable", null, null);
        long endTime = System.nanoTime();
        System.out.println("Build Time: " + (endTime - startTime));

        // Create the kernel
        cl_kernel kernel = CL.clCreateKernel(program, kernelName, null);

        // measure the data transfer time.
        startTime = System.nanoTime();

        // Allocate the memory objects for the input- and output data
        cl_mem memObjects[] = new cl_mem[clParams.size()];
        for (int i = 0; i < memObjects.length; i++) {
            OpenCLParameter clParam = clParams.get(i);
            long flags = CL.CL_MEM_COPY_HOST_PTR;
            if (clParam.isArray()) {
                if (clParam.isReadOnly()) {
                    flags |= CL.CL_MEM_READ_ONLY;
                } else {
                    flags |= CL.CL_MEM_READ_WRITE;
                }
            } else {
                flags |= CL.CL_MEM_READ_ONLY;
            }
            memObjects[i] = CL.clCreateBuffer(context, flags, clParam.getType() * clParam.getArrayLength(), clParam.getPointer(), null);

            // Set the arguments for the kernel
            CL.clSetKernelArg(kernel, i, Sizeof.cl_mem, Pointer.to(memObjects[i]));
        }

        endTime = System.nanoTime();
        System.out.println("Transfer Data Time: " + (endTime - startTime));

        // Set the work-item dimensions
        long global_work_size[] = new long[]{ParallelOptions.WorkSize.getValue()};

        cl_event event = CL.clCreateUserEvent(context, null);

        // Execute the kernel
        CL.clEnqueueNDRangeKernel(commandQueue, kernel, 1, null, global_work_size, null, 0, null, event);

        // Wait for kernel completion.
        CL.clWaitForEvents(1, new cl_event[]{event});

        long[] time_start = new long[1];
        long[] time_end = new long[1];
        long total_time;

        CL.clGetEventProfilingInfo(event, CL.CL_PROFILING_COMMAND_START, Sizeof.cl_long, Pointer.to(time_start), null);
        CL.clGetEventProfilingInfo(event, CL.CL_PROFILING_COMMAND_END, Sizeof.cl_long, Pointer.to(time_end), null);
        total_time = time_end[0] - time_start[0];
        System.out.println("Kernel Execution Time: " + (total_time));

        // Read the output data and measure read data time.
        startTime = System.nanoTime();

        for (int i = 0; i < memObjects.length; i++) {
            OpenCLParameter clParam = clParams.get(i);
            if (!clParam.isReadOnly()) {
                CL.clEnqueueReadBuffer(commandQueue, memObjects[i], CL.CL_TRUE, 0, clParam.getType() * clParam.getArrayLength(), clParam.getPointer(), 0, null, null);
            }
        }

        endTime = System.nanoTime();

        System.out.println("Read Data Time: " + (endTime - startTime));

        // Release kernel, program, and memory objects
        for (int i = 0; i < memObjects.length; i++) {
            if (!clParams.get(i).isReadOnly())
                CL.clReleaseMemObject(memObjects[i]);
        }

        CL.clReleaseKernel(kernel);
        CL.clReleaseProgram(program);
        CL.clReleaseCommandQueue(commandQueue);
        CL.clReleaseContext(context);
    }

    public static Class<?> compile(JavaMethod jm, String signature) {
        String clazzName = jm.getDeclaringClass().getName();
        clazzName = clazzName.substring(1, clazzName.length() - 1);
        String newClazzName = "Parallel" + clazzName;
        byte[] bytecode = generateBytecode(newClazzName, clazzName, jm, signature);
        return new OwnClassLoader().defineClass(newClazzName, bytecode);
    }

    @SuppressWarnings("unused")
    private static byte[] generateBytecode(String newClazzName, String originalName, JavaMethod jm, String signature) {
        ClassWriter cw = new ClassWriter(0);
        FieldVisitor fv;
        MethodVisitor mv;
        AnnotationVisitor av0;

        cw.visit(51, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER, newClazzName, null, "java/lang/Object", null);

        {
            av0 = cw.visitAnnotation("Lcom/oracle/graal/api/replacements/ClassSubstitution;", true);
            av0.visit("className", originalName);
            av0.visit("optional", Boolean.TRUE);
            av0.visitEnd();
        }
        {
            mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V");
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
        {
            mv = cw.visitMethod(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, jm.getName(), signature, null, null);
            {
                av0 = mv.visitAnnotation("Lcom/oracle/graal/api/replacements/MethodSubstitution;", true);
                av0.visit("forced", Boolean.TRUE);
                av0.visitEnd();
            }
            mv.visitCode();

            // number of parameters.
            int paramCount = jm.getSignature().getParameterCount(false);
            System.out.println("param count: " + paramCount);
            // create object array to hold parameters.
            mv.visitInsn(Opcodes.ICONST_0 + paramCount);
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            mv.visitVarInsn(Opcodes.ASTORE, paramCount);

            // add parameters to object array.
            for (int i = 0; i < paramCount; i++) {
                mv.visitVarInsn(Opcodes.ALOAD, paramCount);
                mv.visitInsn(Opcodes.ICONST_0 + i);
                mv.visitVarInsn(Opcodes.ALOAD, i);
                mv.visitInsn(Opcodes.AASTORE);
            }

            int nextIndex = paramCount + 1;

            mv.visitFieldInsn(Opcodes.GETSTATIC, "com/edinburgh/parallel/opencl/ParallelMethods", "methods", "Ljava/util/concurrent/ConcurrentHashMap;");
            mv.visitLdcInsn(newClazzName + "." + jm.getName());
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ConcurrentHashMap", "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
            mv.visitTypeInsn(Opcodes.CHECKCAST, "com/oracle/graal/nodes/StructuredGraph");
            mv.visitVarInsn(Opcodes.ASTORE, nextIndex);
            mv.visitVarInsn(Opcodes.ALOAD, nextIndex);
            mv.visitVarInsn(Opcodes.ALOAD, paramCount);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/edinburgh/parallel/opencl/ParallelUtil", "run", "(Lcom/oracle/graal/nodes/StructuredGraph;[Ljava/lang/Object;)V");
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(3, nextIndex + 1);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static class OwnClassLoader extends ClassLoader {

        public Class<?> defineClass(String name, byte[] b) {
            return defineClass(name, b, 0, b.length);
        }
    }

}
