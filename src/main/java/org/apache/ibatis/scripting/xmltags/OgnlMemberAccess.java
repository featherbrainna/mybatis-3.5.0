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
package org.apache.ibatis.scripting.xmltags;

import ognl.MemberAccess;
import org.apache.ibatis.reflection.Reflector;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Member;
import java.util.Map;

/**
 * 实现 ognl.MemberAccess 接口，OGNL 成员访问器实现类（对象成员访问控制器）
 * The {@link MemberAccess} class that based on <a href=
 * 'https://github.com/jkuhnert/ognl/blob/OGNL_3_2_1/src/java/ognl/DefaultMemberAccess.java'>DefaultMemberAccess</a>.
 *
 * @author Kazuki Shimizu
 * @since 3.5.0
 *
 * @see <a href=
 *      'https://github.com/jkuhnert/ognl/blob/OGNL_3_2_1/src/java/ognl/DefaultMemberAccess.java'>DefaultMemberAccess</a>
 * @see <a href='https://github.com/jkuhnert/ognl/issues/47'>#47 of ognl</a>
 */
class OgnlMemberAccess implements MemberAccess {

  /**
   * 是否可以修改成员的可访问（依据是否有反射操作权限，默认true）
   */
  private final boolean canControlMemberAccessible;

  /**
   * 构造器初始化属性 canControlMemberAccessible
   */
  OgnlMemberAccess() {
    this.canControlMemberAccessible = Reflector.canControlMemberAccessible();
  }

  /**
   * 设置 Member 反射对象可访问，不受访问修饰符影响
   * @param context
   * @param target
   * @param member
   * @param propertyName
   * @return
   */
  @Override
  public Object setup(Map context, Object target, Member member, String propertyName) {
    Object result = null;
    //1.判断是否可以修改
    if (isAccessible(context, target, member, propertyName)) {
      //强制转型为反射对象
      AccessibleObject accessible = (AccessibleObject) member;
      //2.不可访问，则设置为可访问
      if (!accessible.isAccessible()) {
        //标记原来是不可访问
        result = Boolean.FALSE;
        //修改可访问
        accessible.setAccessible(true);
      }
    }
    return result;
  }

  /**
   * 复原 Member 反射对象访问状态
   * @param context
   * @param target
   * @param member
   * @param propertyName
   * @param state
   */
  @Override
  public void restore(Map context, Object target, Member member, String propertyName,
      Object state) {
    //修改为原来的可访问
    if (state != null) {
      ((AccessibleObject) member).setAccessible((Boolean) state);
    }
  }

  /**
   * 是否可以访问成员，默认true
   * @param context
   * @param target
   * @param member
   * @param propertyName
   * @return
   */
  @Override
  public boolean isAccessible(Map context, Object target, Member member, String propertyName) {
    return canControlMemberAccessible;
  }

}
