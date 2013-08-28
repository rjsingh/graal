package com.edinburgh.parallel.opencl;

import org.jocl.*;

public class OpenCLParameter {

    private boolean isArray;
    private boolean isReadOnly;
    private Object javaParam;
    private Object flatParam;
    private int type;
    private int length;
    private Pointer pToObj;

    public OpenCLParameter(Object javaParam, Object flatParam, Pointer p, int type, boolean isArray, boolean isReadOnly, int length) {
        this.javaParam = javaParam;
        this.flatParam = flatParam;
        this.type = type;
        this.isArray = isArray;
        this.isReadOnly = isReadOnly;
        this.length = length;
        this.pToObj = p;
    }

    public Object getFlatArray() {
        return flatParam;
    }

    public int getArrayLength() {
        return length;
    }

    public int getType() {
        return type;
    }

    public Object getJavaParam() {
        return javaParam;
    }

    public boolean isArray() {
        return isArray;
    }

    public boolean isReadOnly() {
        return isReadOnly;
    }

    public Pointer getPointer() {
        return pToObj;
    }

    @Override
    public String toString() {
        String result = "Array: " + isArray;
        result += "\nLength: " + length;
        result += "\nRead Only: " + isReadOnly;
        return result;
    }
}
