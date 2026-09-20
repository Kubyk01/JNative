import java.io.IOException;

public class Test {


    public static void main(String[] args) throws IOException {
        final int b = Example.lol();
        Thread.currentThread();

        for (int i = 0; i < 100; i++) {
            var x = new Test2(i);
        }

        throw new IllegalArgumentException("test");
    }
}

class Example {
    static int a = 5;
    static int testValuedontinit = 15;

    static {
        String lol = "dont add this";
    }
    public static int lol(){
        return a;
    }

}

class Test2{
    public int b;

    public Test2(int b) {
        this.b = b;
    }
}