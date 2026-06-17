package com.example.sample;

import java.util.List;
import java.util.ArrayList;

/**
 * A sample Java class used as a test fixture.
 */
public class SampleJavaClass {

    private final String name;

    public SampleJavaClass(String name) {
        this.name = name;
    }

    /**
     * Returns a greeting string.
     */
    public String greet() {
        return "Hello, " + name + "!";
    }

    public int compute(int x, int y) {
        return x + y;
    }

    public interface Processor {
        String process(String input);
        int count(List<String> items);
    }

    public static class NestedHelper {
        public String help() {
            return "helping";
        }
    }
}
