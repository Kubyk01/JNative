import java.io.IOException;

public class Test {


    public static void main(String[] args) throws IOException {
        final int b = Example.lol();
        Thread.currentThread();
        long num = 0;
        var x = "";
        for (long i = 0; i < 100_000; i++) {
            x = new Test2((int) num).toString();
            num++;

        }
        System.out.println(x);
        System.out.println(num);
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