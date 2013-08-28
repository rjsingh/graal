package com.edinburgh.parallel.opencl;

public class CodeGenBuffer {

    private StringBuffer sb;
    private static final String NEWLINE = System.getProperty("line.separator");
    private String spaces;
    private static final String SPACE_TYPE = " ";
    private boolean commentsEnabled;

    public CodeGenBuffer(boolean comments) {
        sb = new StringBuffer();
        spaces = "";
        commentsEnabled = comments;
    }

    public void beginBlock() {
        incSpaces();
    }

    public void endBlock() {
        decSpaces();
    }

    public void emitComment(String s) {
        if (commentsEnabled) {
            sb.append(spaces + "/* " + s + " */" + NEWLINE);
        }
    }

    public void beginBlockComment() {
        if (commentsEnabled) {
            sb.append(spaces + "/*" + NEWLINE);
            incSpaces();
        }
    }

    public void endBlockComment() {
        if (commentsEnabled) {
            decSpaces();
            sb.append(spaces + "*/" + NEWLINE);
        }
    }

    public void emitString(String s) {
        sb.append(spaces + s + NEWLINE);
    }

    public void emitStringNoNL(String s) {
        sb.append(spaces + s);
    }

    public void emitStringNoSpaces(String s) {
        sb.append(s);
    }

    private void incSpaces() {
        spaces += SPACE_TYPE;
    }

    private void decSpaces() {
        spaces = spaces.substring(0, spaces.length() - 1);
    }

    public String getCode() {
        return sb.toString();
    }
}
