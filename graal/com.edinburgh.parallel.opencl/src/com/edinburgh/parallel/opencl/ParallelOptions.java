package com.edinburgh.parallel.opencl;

import com.oracle.graal.options.*;

public class ParallelOptions {

    @Option(help = "See comments during compilation") public static final OptionValue<Boolean> EnableComments = new OptionValue<>(false);
    @Option(help = "Print the OpenCL code generated") public static final OptionValue<Boolean> PrintCode = new OptionValue<>(false);
    @Option(help = "Execute code on GPU") public static final OptionValue<Boolean> Execute = new OptionValue<>(true);
    @Option(help = "Use CPU or GPU") public static final OptionValue<Boolean> UseCPU = new OptionValue<>(false);
    @Option(help = "Set global work size") public static final OptionValue<Integer> WorkSize = new OptionValue<>(1000);
}
