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
package org.apache.ibatis.reflection;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

import java.lang.reflect.*;
import java.util.*;
import java.util.Map.Entry;

/**
 * 反射器，每个 Reflector 对象对应一个类。Reflector 会缓存反射操作需要的类的信息。
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 *
 * @author Clinton Begin
 */
public class Reflector {

  /**
   * 对应的Class类型
   */
  private final Class<?> type;
  /**
   * 类的可读属性名称集合
   */
  private final String[] readablePropertyNames;
  /**
   * 类的可写属性名称集合
   */
  private final String[] writeablePropertyNames;
  /**
   * 属性对应的setting方法映射
   * key 属性名称
   * value Invoker对象
   */
  private final Map<String, Invoker> setMethods = new HashMap<>();
  /**
   * 属性对应的getting方法映射
   * key 属性名称
   * value Invoker对象
   */
  private final Map<String, Invoker> getMethods = new HashMap<>();
  /**
   * 属性对应的setting方法的方法参数类型的映射
   * key 属性名称（不是方法名称）
   * value 方法参数类型
   */
  private final Map<String, Class<?>> setTypes = new HashMap<>();
  /**
   * 属性对应的getting方法的返回值类型的映射
   * key 属性名称
   * value 返回值的类型
   */
  private final Map<String, Class<?>> getTypes = new HashMap<>();
  /**
   * 类的默认构造方法
   */
  private Constructor<?> defaultConstructor;
  /**
   * 不区分大小写的属性集合，以全大写属性名称为key，含大小写的属性名称为value
   */
  private Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

  /**
   * 构造方法，初始化所有字段
   * @param clazz
   */
  public Reflector(Class<?> clazz) {
    //设置对应的类
    type = clazz;
    //初始化defaultConstructor
    addDefaultConstructor(clazz);
    //初始化getMethods和getTypes，通过遍历clazz的get方法
    addGetMethods(clazz);
    //初始化setMethods和setTypes，通过遍历clazz的get方法
    addSetMethods(clazz);
    //初始化没有getter/setter方法的字段
    addFields(clazz);
    //根据getMethods和setMethods集合的key，初始化可读/写属性名称集合
    readablePropertyNames = getMethods.keySet().toArray(new String[getMethods.keySet().size()]);
    writeablePropertyNames = setMethods.keySet().toArray(new String[setMethods.keySet().size()]);
    //根据readablePropertyNames和writeablePropertyNames初始化caseInsensitivePropertyMap
    for (String propName : readablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
    for (String propName : writeablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
  }

  /**
   * 依据类初始化默认构造器defaultConstructor字段
   * @param clazz
   */
  private void addDefaultConstructor(Class<?> clazz) {
    Constructor<?>[] consts = clazz.getDeclaredConstructors();
    for (Constructor<?> constructor : consts) {
      //构造方法参数个数为零即默认构造器
      if (constructor.getParameterTypes().length == 0) {
          this.defaultConstructor = constructor;
      }
    }
  }

  /**
   * 初始化类的所有getter方法对应getMethods和getType字段
   * @param cls 类的类型
   */
  private void addGetMethods(Class<?> cls) {
    Map<String, List<Method>> conflictingGetters = new HashMap<>();
    //1.获取类的所有方法反射对象
    Method[] methods = getClassMethods(cls);
    //遍历所有方法
    for (Method method : methods) {
      //2.依据get方法无参数的特性过滤
      if (method.getParameterTypes().length > 0) {
        continue;
      }
      //3.如果方法名以get和is开头则为get方法，进行处理
      String name = method.getName();
      if ((name.startsWith("get") && name.length() > 3)
          || (name.startsWith("is") && name.length() > 2)) {
        //解析方法的属性名
        name = PropertyNamer.methodToProperty(name);
        //4.将name和method键值对加入conflictingGetters集合
        addMethodConflict(conflictingGetters, name, method);//由于可能一个属性同时有get和is方法和不同返回值类型父类方法，所以以list接收同一属性的方法
      }
    }
    //5.依据conflictingGetters初始化本类字段，处理冲突的方法
    resolveGetterConflicts(conflictingGetters);
  }

  /**
   * 处理同一属性的冲突的getter方法集合，并将其设置到对象字段缓存
   * @param conflictingGetters
   */
  private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
    //1.遍历所有get方法
    for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
      Method winner = null;
      String propName = entry.getKey();
      //2.遍历某一属性下的get方法
      for (Method candidate : entry.getValue()) {
        if (winner == null) {
          winner = candidate;
          continue;
        }
        //3.获取方法的返回值类型
        Class<?> winnerType = winner.getReturnType();
        Class<?> candidateType = candidate.getReturnType();
        //3.1.方法返回值类型相等（相等类型的返回值只有同时有is和get方法时）
        if (candidateType.equals(winnerType)) {
          if (!boolean.class.equals(candidateType)) {
            throw new ReflectionException(
                "Illegal overloaded getter method with ambiguous type for property "
                    + propName + " in class " + winner.getDeclaringClass()
                    + ". This breaks the JavaBeans specification and can cause unpredictable results.");
          } else if (candidate.getName().startsWith("is")) {
            //is方法为最终缓存的方法反射对象
            winner = candidate;
          }
          //3.2.winnerType是candidateType的子类则无需处理
        } else if (candidateType.isAssignableFrom(winnerType)) {
          // OK getter type is descendant
          //candidateType是winnerType的子类，则重新赋值winnerType
        } else if (winnerType.isAssignableFrom(candidateType)) {
          //3.3.不断循环处理找到最终的子类方法
          winner = candidate;
        } else {
          //类型不相同且不是父子关系，报错
          throw new ReflectionException(
              "Illegal overloaded getter method with ambiguous type for property "
                  + propName + " in class " + winner.getDeclaringClass()
                  + ". This breaks the JavaBeans specification and can cause unpredictable results.");
        }
      }
      //4.将循环处理后找到的属性的唯一子类方法缓存到本对象字段
      addGetMethod(propName, winner);
    }
  }

  /**
   * 初始化填充单个属性对应的getMethods和getTypes字段
   * @param name 属性名
   * @param method 方法反射对象
   */
  private void addGetMethod(String name, Method method) {
    //校验属性名合法性
    if (isValidPropertyName(name)) {
      //用MethodInvoker封装后缓存到getMethods字段
      getMethods.put(name, new MethodInvoker(method));
      //解析方法的返回值类型
      Type returnType = TypeParameterResolver.resolveReturnType(method, type);
      getTypes.put(name, typeToClass(returnType));
    }
  }

  /**
   * 初始化所有setter方法的setMethods和setType字段
   * @param cls 类的类型
   */
  private void addSetMethods(Class<?> cls) {
    Map<String, List<Method>> conflictingSetters = new HashMap<>();
    //1.获取类的所有方法反对象（包括本类和父类）
    Method[] methods = getClassMethods(cls);
    //遍历所有方法
    for (Method method : methods) {
      String name = method.getName();
      //2.依据set方法的特性进行过滤，以set开头且方法名长度>3，方法参数个数为1个
      if (name.startsWith("set") && name.length() > 3) {
        if (method.getParameterTypes().length == 1) {
          name = PropertyNamer.methodToProperty(name);
          //3.将name和method键值对加入conflictingSetters集合
          addMethodConflict(conflictingSetters, name, method);
        }
      }
    }
    //4.依据conflictingGetters初始化本类字段，处理冲突的方法
    resolveSetterConflicts(conflictingSetters);
  }

  private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
    List<Method> list = conflictingMethods.computeIfAbsent(name, k -> new ArrayList<>());
    list.add(method);
  }

  /**
   * 处理同一属性的冲突的setter方法集合，并将其设置到对象字段缓存
   * @param conflictingSetters
   */
  private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
    //1.遍历conflictingSetters集合
    for (String propName : conflictingSetters.keySet()) {
      //获取属性对应得setter方法集合
      List<Method> setters = conflictingSetters.get(propName);
      //获取已初始化好得属性得get返回值类型
      Class<?> getterType = getTypes.get(propName);
      Method match = null;
      ReflectionException exception = null;
      //2.遍历setter方法集合
      for (Method setter : setters) {
        Class<?> paramType = setter.getParameterTypes()[0];
        //setter方法参数与预计得类类型一致，说明找到匹配的stter方法
        if (paramType.equals(getterType)) {
          // should be the best match
          match = setter;
          break;
        }
        if (exception == null) {
          try {
            //当前方法未匹配，则找方法参数是子类的方法赋值给match
            match = pickBetterSetter(match, setter, propName);
          } catch (ReflectionException e) {
            // there could still be the 'best match'
            match = null;
            exception = e;
          }
        }
      }
      //没找到匹配的set方法抛出异常
      if (match == null) {
        throw exception;
      } else {
        //找到匹配的setter方法初始化单个属性的字段值
        addSetMethod(propName, match);
      }
    }
  }

  /**
   * 获取更合适的setter方法，参数为子类的更合适
   * @param setter1
   * @param setter2
   * @param property
   * @return
   */
  private Method pickBetterSetter(Method setter1, Method setter2, String property) {
    if (setter1 == null) {
      return setter2;
    }
    Class<?> paramType1 = setter1.getParameterTypes()[0];
    Class<?> paramType2 = setter2.getParameterTypes()[0];
    //如果paramType2可以赋值给paramType1，即paramType2是paramType1子类或同类
    if (paramType1.isAssignableFrom(paramType2)) {
      return setter2;
    } else if (paramType2.isAssignableFrom(paramType1)) {
      return setter1;
    }
    throw new ReflectionException("Ambiguous setters defined for property '" + property + "' in class '"
        + setter2.getDeclaringClass() + "' with types '" + paramType1.getName() + "' and '"
        + paramType2.getName() + "'.");
  }

  /**
   * 初始化填充单个属性对应的setMethods和setTypes字段
   * @param name 属性名
   * @param method 方法反射对象
   */
  private void addSetMethod(String name, Method method) {
    if (isValidPropertyName(name)) {
      setMethods.put(name, new MethodInvoker(method));
      Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
      setTypes.put(name, typeToClass(paramTypes[0]));
    }
  }

  /**
   * 将Type对象转换为Class对象
   * 普通类返回普通类，泛型类型返回泛型原始类型，泛型数组返回数组类型的class，泛型变量类型返回Object.class。
   * @param src Type对象
   * @return 对应的Class对象
   */
  private Class<?> typeToClass(Type src) {
    Class<?> result = null;
    // 1.普通类型，直接使用类
    if (src instanceof Class) {
      result = (Class<?>) src;
    // 2.泛型类型，使用泛型的原始类型
    } else if (src instanceof ParameterizedType) {
      result = (Class<?>) ((ParameterizedType) src).getRawType();
    // 3.泛型数组，获得具体数组class
    } else if (src instanceof GenericArrayType) {
      Type componentType = ((GenericArrayType) src).getGenericComponentType();
      if (componentType instanceof Class) {// 数组元素是普通类型
        result = Array.newInstance((Class<?>) componentType, 0).getClass();
      } else {
        //不是普通类型
        Class<?> componentClass = typeToClass(componentType);// 递归该方法，返回类
        result = Array.newInstance(componentClass, 0).getClass();
      }
    }
    // 4.都不符合，使用 Object 类
    if (result == null) {
      result = Object.class;
    }
    return result;
  }

  /**
   * 处理没有getter/setter所有方法的字段初始换填充对象字段
   * @param clazz 类类型
   */
  private void addFields(Class<?> clazz) {
    //1.获取本类定义的全部字段
    Field[] fields = clazz.getDeclaredFields();
    //2.遍历字段反射对象
    for (Field field : fields) {
      //当setMethods集合不包含同名属性时，将其记录到setMethods集合和setType集合
      if (!setMethods.containsKey(field.getName())) {
        // issue #379 - removed the check for final because JDK 1.5 allows
        // modification of final fields through reflection (JSR-133). (JGB)
        // pr #16 - final static can only be set by the classloader
        //过滤掉final和static修饰的字段
        int modifiers = field.getModifiers();
        if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
          //3.填充setMethods集合和setType集合
          addSetField(field);
        }
      }
      //当getMethods集合不包含同名属性时，将其记录到getMethods集合和getType集合
      if (!getMethods.containsKey(field.getName())) {
        //3.填充getMethods集合和getType集合
        addGetField(field);
      }
    }
    if (clazz.getSuperclass() != null) {
      //4.递归调用处理父类字段
      addFields(clazz.getSuperclass());
    }
  }

  /**
   * 单个字段初始化填充setMethods和setTypes
   * @param field 字段反射对象
   */
  private void addSetField(Field field) {
    //校验字段名是否合法
    if (isValidPropertyName(field.getName())) {
      //填充setMethods集合，使用字段的SetFieldInvoker
      setMethods.put(field.getName(), new SetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      //填充setTypes集合
      setTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  /**
   * 单个字段初始化填充getMethods和getTypes
   * @param field 字段反射对象
   */
  private void addGetField(Field field) {
    //校验字段名是否合法
    if (isValidPropertyName(field.getName())) {
      //填充getMethods集合，使用字段的GetFieldInvoker
      getMethods.put(field.getName(), new GetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      //填充getTypes集合
      getTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private boolean isValidPropertyName(String name) {
    return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
  }

  /**
   * 获取类的所有方法反射对象（包括所有权限修饰符的方法、包括所有接口和继承的父类的方法）
   * 顺序是先添加子类方法，再添加父类方法，与子类同签名的父类方法会被覆盖。（签名包括返回值、函数名、参数）
   * This method returns an array containing all methods
   * declared in this class and any superclass.
   * We use this method, instead of the simpler Class.getMethods(),
   * because we want to look for private methods as well.
   *
   * @param cls The class
   * @return An array containing all methods in this class
   */
  private Method[] getClassMethods(Class<?> cls) {
    //用于记录指定类中定义的全部方法的唯一签名以及对应的Method对象
    Map<String, Method> uniqueMethods = new HashMap<>();
    Class<?> currentClass = cls;
    //currentClass类对象不为null且类不为Object时进行方法反射对象获取
    while (currentClass != null && currentClass != Object.class) {
      //1.获取本类的方法反射对象到uniqueMethods，不包括继承的方法反射对象
      addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

      // we also need to look for interface methods -
      // because the class may be abstract
      //2.获取本类的接口的方法反射对象到uniqueMethods
      Class<?>[] interfaces = currentClass.getInterfaces();
      for (Class<?> anInterface : interfaces) {
        addUniqueMethods(uniqueMethods, anInterface.getMethods());
      }

      //3.索引到父类，在下一轮循环处理父类的方法反射对象
      currentClass = currentClass.getSuperclass();
    }

    Collection<Method> methods = uniqueMethods.values();
    //转换成Methods数组返回
    return methods.toArray(new Method[methods.size()]);
  }

  /**
   * 将方法反射对象集合加入uniqueMethods对象存储，加入的键方法签名是唯一的
   * @param uniqueMethods map容器
   * @param methods 方法反射对象集合
   */
  private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
    for (Method currentMethod : methods) {
      //不是桥接方法时处理
      if (!currentMethod.isBridge()) {
        //1.获取方法签名。签名格式：返回值类型#方法名称：参数类型列表
        String signature = getSignature(currentMethod);
        // check to see if the method is already known
        // if it is known, then an extended class must have
        // overridden a method
        //2.子类不存在此方法签名时加入map,在map中不存在时加入map
        if (!uniqueMethods.containsKey(signature)) {
          uniqueMethods.put(signature, currentMethod);
        }
      }
    }
  }

  /**
   * 获取方法签名，格式为：returnType#methodName:parameter,parameter
   * @param method
   * @return
   */
  private String getSignature(Method method) {
    StringBuilder sb = new StringBuilder();
    Class<?> returnType = method.getReturnType();
    if (returnType != null) {
      sb.append(returnType.getName()).append('#');
    }
    sb.append(method.getName());
    Class<?>[] parameters = method.getParameterTypes();
    for (int i = 0; i < parameters.length; i++) {
      if (i == 0) {
        sb.append(':');
      } else {
        sb.append(',');
      }
      sb.append(parameters[i].getName());
    }
    return sb.toString();
  }

  /**
   * 判断是否可以修改可访问性（是否可以反射操作）。有权限返回true，无权限返回false
   * Checks whether can control member accessible.
   *
   * @return If can control member accessible, it return {@literal true}
   * @since 3.5.0
   */
  public static boolean canControlMemberAccessible() {
    try {
      SecurityManager securityManager = System.getSecurityManager();
      if (null != securityManager) {
        securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
      }
    } catch (SecurityException e) {
      return false;
    }
    return true;
  }

  /**
   * Gets the name of the class the instance provides information for
   *
   * @return The class name
   */
  public Class<?> getType() {
    return type;
  }

  public Constructor<?> getDefaultConstructor() {
    if (defaultConstructor != null) {
      return defaultConstructor;
    } else {
      throw new ReflectionException("There is no default constructor for " + type);
    }
  }

  public boolean hasDefaultConstructor() {
    return defaultConstructor != null;
  }

  public Invoker getSetInvoker(String propertyName) {
    Invoker method = setMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  public Invoker getGetInvoker(String propertyName) {
    Invoker method = getMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  /**
   * Gets the type for a property setter
   *
   * @param propertyName - the name of the property
   * @return The Class of the property setter
   */
  public Class<?> getSetterType(String propertyName) {
    Class<?> clazz = setTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets the type for a property getter
   *
   * @param propertyName - the name of the property
   * @return The Class of the property getter
   */
  public Class<?> getGetterType(String propertyName) {
    Class<?> clazz = getTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets an array of the readable properties for an object
   *
   * @return The array
   */
  public String[] getGetablePropertyNames() {
    return readablePropertyNames;
  }

  /**
   * Gets an array of the writable properties for an object
   *
   * @return The array
   */
  public String[] getSetablePropertyNames() {
    return writeablePropertyNames;
  }

  /**
   * Check to see if a class has a writable property by name
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a writable property by the name
   */
  public boolean hasSetter(String propertyName) {
    return setMethods.keySet().contains(propertyName);
  }

  /**
   * Check to see if a class has a readable property by name
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a readable property by the name
   */
  public boolean hasGetter(String propertyName) {
    return getMethods.keySet().contains(propertyName);
  }

  public String findPropertyName(String name) {
    return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
  }
}
