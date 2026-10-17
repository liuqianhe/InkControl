package com.inkcontrol.core;

public interface ConfigStore {
    Configuration load() throws Exception;
    void save(Configuration configuration) throws Exception;
}
