package org.jetbrains.research.kex.test.spider.computer.client;

import org.jetbrains.research.kex.test.spider.computer.lib.Computer;

public class Main {
    public static void main(String[] args) {
        // ok
        Computer computer1 = new Computer();
        computer1.boot();
        computer1.selectOS("win");
        computer1.loadOS();

        // wrong sequence
        Computer computer2 = new Computer();
        computer2.boot();
        computer2.loadOS();
        computer2.selectOS("linux");

        // shift from finishstate
        Computer computer3 = new Computer();
        computer3.shutdown(); // finishstate!
        computer3.boot();

        // requires: wrong OS name (expected 'win' or 'linux')
        Computer computer4 = new Computer();
        computer4.boot();
        computer4.selectOS("osx");
    }
}