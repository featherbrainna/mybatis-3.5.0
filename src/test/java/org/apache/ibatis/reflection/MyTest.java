package org.apache.ibatis.reflection;

import org.apache.ibatis.reflection.property.PropertyCopier;
import org.junit.jupiter.api.Test;

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
}

class A{
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