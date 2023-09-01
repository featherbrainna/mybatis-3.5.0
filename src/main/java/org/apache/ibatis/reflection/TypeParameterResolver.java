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

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;

/**
 * 当存在复杂的继承关系以及泛型定义时， TypeParameterResolver 可以帮助我们解析字段、方法参数或方法返回值的类型。
 * 且解析成 mybatis 框架中的类型数据结构
 * @author Iwao AVE!
 */
public class TypeParameterResolver {

  /**
   * 解析字段在srcType中的真实声明类型
   * @return The field type as {@link Type}. If it has type parameters in the declaration,<br>
   *         they will be resolved to the actual runtime {@link Type}s.
   */
  public static Type resolveFieldType(Field field, Type srcType) {
    //1.获取字段的声明类型
    Type fieldType = field.getGenericType();
    //2.获取字段定义所在的类的Class对象
    Class<?> declaringClass = field.getDeclaringClass();
    //3.调用resolveType（）方法进行后续处理
    return resolveType(fieldType, srcType, declaringClass);
  }

  /**
   * 解析方法返回值在srcType中的真实声明类型
   * @return The return type of the method as {@link Type}. If it has type parameters in the declaration,<br>
   *         they will be resolved to the actual runtime {@link Type}s.
   */
  public static Type resolveReturnType(Method method, Type srcType) {
    //1.获取方法返回声明类型
    Type returnType = method.getGenericReturnType();
    //2.获取定义所在的类
    Class<?> declaringClass = method.getDeclaringClass();
    //3.解析方法返回参数类型
    return resolveType(returnType, srcType, declaringClass);
  }

  /**
   * 解析方法参数值在srcType中的真实声明类型
   * @return The parameter types of the method as an array of {@link Type}s. If they have type parameters in the declaration,<br>
   *         they will be resolved to the actual runtime {@link Type}s.
   */
  public static Type[] resolveParamTypes(Method method, Type srcType) {
    //1.获取方法参数声明类型
    Type[] paramTypes = method.getGenericParameterTypes();
    //2.获取方法定义所在的类
    Class<?> declaringClass = method.getDeclaringClass();
    //3.解析方法参数各个类型
    Type[] result = new Type[paramTypes.length];
    for (int i = 0; i < paramTypes.length; i++) {
      result[i] = resolveType(paramTypes[i], srcType, declaringClass);
    }
    return result;
  }

  /**
   * 解析类型，依据type类型使用框架内置的多种Type实现类返回
   * @param type 原始类型
   * @param srcType 解析操作的环境子类类型
   * @param declaringClass 声明的父类
   * @return 解析后的类型
   */
  private static Type resolveType(Type type, Type srcType, Class<?> declaringClass) {
    //解析TypeVariable类型。此时原始的类型不会是通配符类型，只有在解析泛型类型时会遇到通配符类型。
    if (type instanceof TypeVariable) {
      return resolveTypeVar((TypeVariable<?>) type, srcType, declaringClass);
      //解析ParameterizedType类型
    } else if (type instanceof ParameterizedType) {
      return resolveParameterizedType((ParameterizedType) type, srcType, declaringClass);
      //解析GenericArrayType类型
    } else if (type instanceof GenericArrayType) {
      return resolveGenericArrayType((GenericArrayType) type, srcType, declaringClass);
    } else {
      //class类型
      return type;
    }
  }

  /**
   * 解析泛型数组类型，解析genericArrayType为框架内的type实现（主要要解析数组元素类型，即各种java类型的元素）
   * @param genericArrayType 待解析的genericArrayType对象
   * @param srcType 解析操作的环境子类类型
   * @param declaringClass 声明的类
   * @return
   */
  private static Type resolveGenericArrayType(GenericArrayType genericArrayType, Type srcType, Class<?> declaringClass) {
    //1.获取数组元素类型
    Type componentType = genericArrayType.getGenericComponentType();
    Type resolvedComponentType = null;
    //2.根据数组元素类型选择合适的方法进行解析，数组元素类型不存在通配符类型
    if (componentType instanceof TypeVariable) {
      resolvedComponentType = resolveTypeVar((TypeVariable<?>) componentType, srcType, declaringClass);
    } else if (componentType instanceof GenericArrayType) {
      resolvedComponentType = resolveGenericArrayType((GenericArrayType) componentType, srcType, declaringClass);
    } else if (componentType instanceof ParameterizedType) {
      resolvedComponentType = resolveParameterizedType((ParameterizedType) componentType, srcType, declaringClass);
    }
    //3.根据解析后的数组项类型构造返回
    if (resolvedComponentType instanceof Class) {
      return Array.newInstance((Class<?>) resolvedComponentType, 0).getClass();
    } else {
      return new GenericArrayTypeImpl(resolvedComponentType);
    }
  }

  /**
   * 解析泛型类型，解析parameterizedType为框架内的type实现（同时要解析泛型参数类型，各种java类型的泛型参数）
   * @param parameterizedType 待解析的parameterizedType对象
   * @param srcType 解析操作的环境子类类型
   * @param declaringClass 声明的父类
   * @return 解析后的类型
   */
  private static ParameterizedType resolveParameterizedType(ParameterizedType parameterizedType, Type srcType, Class<?> declaringClass) {
    //1.获取parameterizedType泛型的原始class对象，List<Intger> -> List
    Class<?> rawType = (Class<?>) parameterizedType.getRawType();
    //2.获取泛型的参数类类型数组，List<Intger> -> Integer
    Type[] typeArgs = parameterizedType.getActualTypeArguments();
    //保存解析后的参数类型数组
    Type[] args = new Type[typeArgs.length];
    //3.遍历typeArgs数组循环解析 <> 中的类型，并解析后赋值给args
    for (int i = 0; i < typeArgs.length; i++) {
      //3.1按类型解析泛型的参数
      if (typeArgs[i] instanceof TypeVariable) {
        args[i] = resolveTypeVar((TypeVariable<?>) typeArgs[i], srcType, declaringClass);
      } else if (typeArgs[i] instanceof ParameterizedType) {
        args[i] = resolveParameterizedType((ParameterizedType) typeArgs[i], srcType, declaringClass);
      } else if (typeArgs[i] instanceof WildcardType) {
        args[i] = resolveWildcardType((WildcardType) typeArgs[i], srcType, declaringClass);
      } else {
        //包含泛型数组和class
        args[i] = typeArgs[i];
      }
    }
    //4.返回解析后的ParameterizedTypeImpl对象
    return new ParameterizedTypeImpl(rawType, null, args);
  }

  /**
   * 解析通配符类型，将wildcardType解析为框架内Type对象
   * @param wildcardType 原始通配符类型对象
   * @param srcType 解析操作的环境子类类型
   * @param declaringClass 声明的类
   * @return 解析后的通配符类型
   */
  private static Type resolveWildcardType(WildcardType wildcardType, Type srcType, Class<?> declaringClass) {
    //委托resolveWildcardTypeBounds方法解析上下界类型
    Type[] lowerBounds = resolveWildcardTypeBounds(wildcardType.getLowerBounds(), srcType, declaringClass);
    Type[] upperBounds = resolveWildcardTypeBounds(wildcardType.getUpperBounds(), srcType, declaringClass);
    //返回解析后的通配符类型
    return new WildcardTypeImpl(lowerBounds, upperBounds);
  }

  /**
   * 解析通配符类型的泛型表达式的上下界类型
   * @param bounds 原始上下界类型
   * @param srcType 解析操作的环境子类类型
   * @param declaringClass 声明的类
   * @return 解析后的通配符上下界类型
   */
  private static Type[] resolveWildcardTypeBounds(Type[] bounds, Type srcType, Class<?> declaringClass) {
    Type[] result = new Type[bounds.length];
    for (int i = 0; i < bounds.length; i++) {
      //上下界类型解析委托resolveTypeVar、resolveParameterizedType、resolveWildcardType方法，上下界不存在泛型数组类型
      if (bounds[i] instanceof TypeVariable) {
        result[i] = resolveTypeVar((TypeVariable<?>) bounds[i], srcType, declaringClass);
      } else if (bounds[i] instanceof ParameterizedType) {
        result[i] = resolveParameterizedType((ParameterizedType) bounds[i], srcType, declaringClass);
      } else if (bounds[i] instanceof WildcardType) {
        result[i] = resolveWildcardType((WildcardType) bounds[i], srcType, declaringClass);
      } else {
        result[i] = bounds[i];
      }
    }
    return result;
  }

  /**
   * 解析泛型参数类型，解析TypeVariable为框架内的Type实现
   * @param typeVar 待解析的typeVar对象
   * @param srcType 解析操作的起始类型
   * @param declaringClass 声明的类
   * @return
   */
  private static Type resolveTypeVar(TypeVariable<?> typeVar, Type srcType, Class<?> declaringClass) {
    Type result = null;
    //1.处理起始类为真实类型
    Class<?> clazz = null;
    if (srcType instanceof Class) {
      clazz = (Class<?>) srcType;
    } else if (srcType instanceof ParameterizedType) {
      ParameterizedType parameterizedType = (ParameterizedType) srcType;
      clazz = (Class<?>) parameterizedType.getRawType();
    } else {
      throw new IllegalArgumentException("The 2nd arg must be Class or ParameterizedType, but was: " + srcType.getClass());
    }

    //2.如果srcType与declaringClass相等，开始解析typeVar
    if (clazz == declaringClass) {
      //泛型中的类型变量有上边界，则返回上边界
      Type[] bounds = typeVar.getBounds();
      if(bounds.length > 0) {
        return bounds[0];
      }
      //无上边界则返回Object
      return Object.class;
    }

    //3.获取声明的父类类型
    Type superclass = clazz.getGenericSuperclass();
    //通过扫描父类进行后续解析，这是递归的入口
    result = scanSuperTypes(typeVar, srcType, declaringClass, clazz, superclass);
    if (result != null) {
      return result;
    }

    //4.获取声明的接口类类型（扩展性的支持，resolveReturnType（）调用时可能是接口方法）
    Type[] superInterfaces = clazz.getGenericInterfaces();
    for (Type superInterface : superInterfaces) {
      result = scanSuperTypes(typeVar, srcType, declaringClass, clazz, superInterface);
      if (result != null) {
        return result;
      }
    }
    //5.若在整个继承结构中都没有解析成功，则返回Object.class
    return Object.class;
  }

  /**
   * 解析泛型参数变量时的底层方法，将类型解析为子类定义的类型而不是父类定义的类型
   * @param typeVar 原始类型
   * @param srcType 子类
   * @param declaringClass typeVar声明的父类
   * @param clazz 子类真实类型
   * @param superclass 子类的父类
   * @return 解析泛型参数后的类型
   */
  private static Type scanSuperTypes(TypeVariable<?> typeVar, Type srcType, Class<?> declaringClass, Class<?> clazz, Type superclass) {
    //1.superclass是泛型类型
    if (superclass instanceof ParameterizedType) {
      ParameterizedType parentAsType = (ParameterizedType) superclass;
      //获取泛型的原始类型
      Class<?> parentAsClass = (Class<?>) parentAsType.getRawType();
      //获取泛型的原始参数列表（父类类型变量数组）
      TypeVariable<?>[] parentTypeVars = parentAsClass.getTypeParameters();
      if (srcType instanceof ParameterizedType) {
        parentAsType = translateParentTypeVars((ParameterizedType) srcType, clazz, parentAsType);
      }
      //2.找到声明此typeVar的父类
      if (declaringClass == parentAsClass) {
        for (int i = 0; i < parentTypeVars.length; i++) {
          //2.1找到相应的参数位置
          if (typeVar == parentTypeVars[i]) {
            //2.2返回子类定义的该位置的真实参数类型
            return parentAsType.getActualTypeArguments()[i];
          }
        }
      }
      //3.没找到声明此typeVar的父类，继续递归解析父类，直到解析到定义该字段的类
      if (declaringClass.isAssignableFrom(parentAsClass)) {
        return resolveTypeVar(typeVar, parentAsType, declaringClass);
      }
    //声明的父类不是泛型类型，故没有泛型参数变量，继续递归解析
    } else if (superclass instanceof Class && declaringClass.isAssignableFrom((Class<?>) superclass)) {
      return resolveTypeVar(typeVar, superclass, declaringClass);
    }
    return null;
  }

  /**
   * TODO 不懂的方法
   * @param srcType
   * @param srcClass
   * @param parentType
   * @return
   */
  private static ParameterizedType translateParentTypeVars(ParameterizedType srcType, Class<?> srcClass, ParameterizedType parentType) {
    Type[] parentTypeArgs = parentType.getActualTypeArguments();
    Type[] srcTypeArgs = srcType.getActualTypeArguments();
    TypeVariable<?>[] srcTypeVars = srcClass.getTypeParameters();
    Type[] newParentArgs = new Type[parentTypeArgs.length];
    boolean noChange = true;
    for (int i = 0; i < parentTypeArgs.length; i++) {
      if (parentTypeArgs[i] instanceof TypeVariable) {
        for (int j = 0; j < srcTypeVars.length; j++) {
          if (srcTypeVars[j] == parentTypeArgs[i]) {
            noChange = false;
            newParentArgs[i] = srcTypeArgs[j];
          }
        }
      } else {
        newParentArgs[i] = parentTypeArgs[i];
      }
    }
    return noChange ? parentType : new ParameterizedTypeImpl((Class<?>)parentType.getRawType(), null, newParentArgs);
  }

  private TypeParameterResolver() {
    super();
  }

  /**
   * ParameterizedType实现类
   * 参数化类型，即泛型
   */
  static class ParameterizedTypeImpl implements ParameterizedType {
    /**
     * 原始类型
     */
    private Class<?> rawType;
    /**
     * 所有者类型
     */
    private Type ownerType;
    /**
     * 泛型参数列表类型。即<>中的类型数组
     */
    private Type[] actualTypeArguments;

    public ParameterizedTypeImpl(Class<?> rawType, Type ownerType, Type[] actualTypeArguments) {
      super();
      this.rawType = rawType;
      this.ownerType = ownerType;
      this.actualTypeArguments = actualTypeArguments;
    }

    @Override
    public Type[] getActualTypeArguments() {
      return actualTypeArguments;
    }

    @Override
    public Type getOwnerType() {
      return ownerType;
    }

    @Override
    public Type getRawType() {
      return rawType;
    }

    @Override
    public String toString() {
      return "ParameterizedTypeImpl [rawType=" + rawType + ", ownerType=" + ownerType + ", actualTypeArguments=" + Arrays.toString(actualTypeArguments) + "]";
    }
  }

  /**
   * WildcardType实现类
   * 泛型表达式（或通配符表达式），即 ? extend Number、? super Integer 这样的表达式。
   * WildcardType 虽然是 Type 的子接口，但却不是 Java 类型中的一种。（在resolveType方法中没有出现，不视为java类型的一种）
   */
  static class WildcardTypeImpl implements WildcardType {
    /**
     * 泛型表达式下界
     */
    private Type[] lowerBounds;
    /**
     * 泛型表达式上界
     */
    private Type[] upperBounds;

    WildcardTypeImpl(Type[] lowerBounds, Type[] upperBounds) {
      super();
      this.lowerBounds = lowerBounds;
      this.upperBounds = upperBounds;
    }

    @Override
    public Type[] getLowerBounds() {
      return lowerBounds;
    }

    @Override
    public Type[] getUpperBounds() {
      return upperBounds;
    }
  }

  /**
   * GenericArrayType实现
   * 泛型数组java类型
   */
  static class GenericArrayTypeImpl implements GenericArrayType {
    /**
     * 数组元素类型
     */
    private Type genericComponentType;

    GenericArrayTypeImpl(Type genericComponentType) {
      super();
      this.genericComponentType = genericComponentType;
    }

    @Override
    public Type getGenericComponentType() {
      return genericComponentType;
    }
  }
}
