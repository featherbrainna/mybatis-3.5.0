/**
 *    Copyright 2009-2018 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.reflection.property;

import org.apache.ibatis.reflection.Reflector;

import java.lang.reflect.Field;

/**
 * 属性拷贝 工具类，
 * @author Clinton Begin
 */
public final class PropertyCopier {

  /**
   * 构造器私有，静态工具类
   */
  private PropertyCopier() {
    // Prevent Instantiation of Static Class
  }

  /**
   * 严格的同类属性拷贝方法，属于浅拷贝
   * @param type 类类型
   * @param sourceBean 源对象
   * @param destinationBean 目标对象
   */
  public static void copyBeanProperties(Class<?> type, Object sourceBean, Object destinationBean) {
    Class<?> parent = type;
    while (parent != null) {
      //1.获取当前类的所有字段
      final Field[] fields = parent.getDeclaredFields();
      //2.遍历处理字段
      for(Field field : fields) {
        try {
          try {
            //3.复制字段值
            field.set(destinationBean, field.get(sourceBean));
          } catch (IllegalAccessException e) {
            //设置访问修饰符
            if (Reflector.canControlMemberAccessible()) {
              field.setAccessible(true);
              field.set(destinationBean, field.get(sourceBean));
            } else {
              throw e;
            }
          }
        } catch (Exception e) {
          // Nothing useful to do, will only fail on final fields, which will be ignored.
          //忽略异常
        }
      }
      //4.获取父类，循环处理父类字段
      parent = parent.getSuperclass();
    }
  }

}
