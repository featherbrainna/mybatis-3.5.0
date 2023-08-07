package org.apache.ibatis.reflection;

import org.apache.ibatis.reflection.property.PropertyCopier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author 王忠义
 * @version 1.0
 * @date 2023/8/6 11:41
 */
public class MyTest {

    @Test
    void test1() {
        A a = new A("王忠义");
        B b = new B();
        //注：只能同类拷贝，无法跨类拷贝
        PropertyCopier.copyBeanProperties(A.class,a,b);
        System.out.println(b.getName());
    }

    @Test
    void test2() throws NoSuchFieldException {
        C c = new C();
//        Reflector reflector = new Reflector(c.getClass());
//        Class<?> list = reflector.getGetterType("list");
//        System.out.println(list.toString());
        Class<? extends C> aClass = c.getClass();
        Field field = aClass.getField("list");
        //测试获取字段的声明类型
        Type type = field.getGenericType();
        System.out.println(type);
        Type type1 = TypeParameterResolver.resolveFieldType(field, aClass);
        System.out.println(type1.getTypeName());
    }

    @Test
    void test3() throws NoSuchFieldException {
        System.out.println(SubClassA.class.getSuperclass());
        //只返回public的字段反射对象
        Field field = SubClassA.class.getSuperclass().getDeclaredField("map");
        System.out.println(field.getGenericType());

        //特点，可以解析出子类对父类字段类型的定义的变化！！！
        Type type = TypeParameterResolver.resolveFieldType(field, SubClassA.class);
        System.out.println(type);
    }
}

class A {
    String name;

    public A() {
    }

    public A(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
class B{
    String name;

    public B() {
    }

    public B(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
class C{
    public List<Integer> list = new ArrayList<>();

    public C(List<Integer> list) {
        this.list = list;
    }

    public C() {
    }

    public List<Integer> getList() {
        return list;
    }

    public void setList(List<Integer> list) {
        this.list = list;
    }
}
class ClassA<K,V>{
    private Map<K,V> map;

    public ClassA() {
    }

    public ClassA(Map<K, V> map) {
        this.map = map;
    }

    public Map<K, V> getMap() {
        return map;
    }

    public void setMap(Map<K, V> map) {
        this.map = map;
    }
}
class SubClassA<T> extends ClassA<T,T>{
    public SubClassA() {
    }

    public SubClassA(Map<T, T> map) {
        super(map);
    }
}