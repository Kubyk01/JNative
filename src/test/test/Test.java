public class Test {


    public static void main(String[] args) {
        final int b = Example.lol();
        Thread.currentThread();
        throw new IllegalArgumentException("test" + b + " " + Thread.currentThread().getName());
    }
}

class Example {
    static int a = 5;
    public static int lol(){
        return a;
    }
}