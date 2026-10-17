package com.inkcontrol.core;

public interface FeatureExecutor {
    void apply(Configuration before, Configuration after) throws Exception;
}
