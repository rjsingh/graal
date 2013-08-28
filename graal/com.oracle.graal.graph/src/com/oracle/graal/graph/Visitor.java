package com.oracle.graal.graph;

public interface Visitor {

    public void dispatch(Node n);
}
