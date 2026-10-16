package com.devcli.cli;

/** DevCLI 的 Java 入口；启动装配和交互循环由 CliApplication 承担。 */
public final class Main {
    private Main() { }

    public static void main(String[] args) {
        CliApplication.main(args);
    }
}
