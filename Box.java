public class Box<T> {   // 在类名后用 <T> 声明泛型类型参数

    private T value;   // 类内部直接把 T 当作类型使用

    public void setValue(T value) {
        this.value = value;
    }

    public T getValue() {
        return value;
    }
}