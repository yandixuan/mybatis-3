/**
 *    Copyright 2009-2019 the original author or authors.
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
package org.apache.ibatis.builder.annotation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

import org.apache.ibatis.annotations.Lang;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class ProviderSqlSource implements SqlSource {

  private final Configuration configuration;
  /**
   * `@ProviderXXX` 注解的对应的类
   */
  private final Class<?> providerType;
  private final LanguageDriver languageDriver;
  /**
   * `@ProviderXXX` 注解的对应的方法
   */
  private Method providerMethod;
  /**
   * `@ProviderXXX` 注解的对应的方法的参数名数组
   */
  private String[] providerMethodArgumentNames;
  /**
   * `@ProviderXXX` 注解的对应的方法的参数类型数组
   */
  private Class<?>[] providerMethodParameterTypes;
  /**
   * 若 {@link #providerMethodParameterTypes} 参数有 ProviderContext 类型的，创建 ProviderContext 对象
   */
  private ProviderContext providerContext;
  /**
   * {@link #providerMethodParameterTypes} 参数中，ProviderContext 类型的参数，在数组中的位置
   */
  private Integer providerContextIndex;

  /**
   * @deprecated Please use the {@link #ProviderSqlSource(Configuration, Object, Class, Method)} instead of this.
   */
  @Deprecated
  public ProviderSqlSource(Configuration configuration, Object provider) {
    this(configuration, provider, null, null);
  }

  /**
   * @since 3.4.5
   */
  public ProviderSqlSource(Configuration configuration, Object provider, Class<?> mapperType, Method mapperMethod) {
    String providerMethodName;
    try {
      this.configuration = configuration;
      // 获取@Lang注解
      Lang lang = mapperMethod == null ? null : mapperMethod.getAnnotation(Lang.class);
      // 获取languageDriver
      this.languageDriver = configuration.getLanguageDriver(lang == null ? null : lang.value());
      this.providerType = (Class<?>) provider.getClass().getMethod("type").invoke(provider);
      providerMethodName = (String) provider.getClass().getMethod("method").invoke(provider);
      // 如果方法名为空并且providerType是ProviderMethodResolver的实现类
      if (providerMethodName.length() == 0 && ProviderMethodResolver.class.isAssignableFrom(this.providerType)) {
        // 默认构造器实例化providerMethod，根据创建的ProviderContext解析providerMethod
        this.providerMethod = ((ProviderMethodResolver) this.providerType.getDeclaredConstructor().newInstance())
            .resolveMethod(new ProviderContext(mapperType, mapperMethod, configuration.getDatabaseId()));
      }
      // 如果providerMethod为空
      if (this.providerMethod == null) {
        // @Provider注解的methodName为空 默认为'provideSql'
        providerMethodName = providerMethodName.length() == 0 ? "provideSql" : providerMethodName;
        // 遍历providerType下的方法
        for (Method m : this.providerType.getMethods()) {
          if (providerMethodName.equals(m.getName()) && CharSequence.class.isAssignableFrom(m.getReturnType())) {
            // 对于相同的providerMethod重复则抛出异常
            if (this.providerMethod != null) {
              throw new BuilderException("Error creating SqlSource for SqlProvider. Method '"
                  + providerMethodName + "' is found multiple in SqlProvider '" + this.providerType.getName()
                  + "'. Sql provider method can not overload.");
            }
            this.providerMethod = m;
          }
        }
      }
    } catch (BuilderException e) {
      throw e;
    } catch (Exception e) {
      throw new BuilderException("Error creating SqlSource for SqlProvider.  Cause: " + e, e);
    }
    if (this.providerMethod == null) {
      throw new BuilderException("Error creating SqlSource for SqlProvider. Method '"
          + providerMethodName + "' not found in SqlProvider '" + this.providerType.getName() + "'.");
    }
    // 获取方法参数的别名数组
    this.providerMethodArgumentNames = new ParamNameResolver(configuration, this.providerMethod).getNames();
    this.providerMethodParameterTypes = this.providerMethod.getParameterTypes();
    // 初始化 providerContext 和 providerContextIndex 属性
    // providerContext存储上下文 比如databaseId,mapperMethod等
    for (int i = 0; i < this.providerMethodParameterTypes.length; i++) {
      Class<?> parameterType = this.providerMethodParameterTypes[i];
      if (parameterType == ProviderContext.class) {
        if (this.providerContext != null) {
          throw new BuilderException("Error creating SqlSource for SqlProvider. ProviderContext found multiple in SqlProvider method ("
              + this.providerType.getName() + "." + providerMethod.getName()
              + "). ProviderContext can not define multiple in SqlProvider method argument.");
        }
        this.providerContext = new ProviderContext(mapperType, mapperMethod, configuration.getDatabaseId());
        this.providerContextIndex = i;
      }
    }
  }

  @Override
  public BoundSql getBoundSql(Object parameterObject) {
    SqlSource sqlSource = createSqlSource(parameterObject);
    return sqlSource.getBoundSql(parameterObject);
  }

  private SqlSource createSqlSource(Object parameterObject) {
    try {
      // 获取绑定的参数个数（providerContext不算在内所以一旦存在就去掉了）
      int bindParameterCount = providerMethodParameterTypes.length - (providerContext == null ? 0 : 1);
      String sql;
      // 根据bindParameterCount的个数反射不同的参数进行反射调用方法获取真实的sql
      if (providerMethodParameterTypes.length == 0) {
        sql = invokeProviderMethod();
      } else if (bindParameterCount == 0) {
        sql = invokeProviderMethod(providerContext);
        // 如果bindParameterCount数量为1 且 parameterObject为null或 根据providerContextIndex的位置判断parameterObject的位置判断parameterObject是不是相应参数类型的子类
      } else if (bindParameterCount == 1
           && (parameterObject == null || providerMethodParameterTypes[providerContextIndex == null || providerContextIndex == 1 ? 0 : 1].isAssignableFrom(parameterObject.getClass()))) {
        sql = invokeProviderMethod(extractProviderMethodArguments(parameterObject));
      } else if (parameterObject instanceof Map) {
        // 若parameterObject是Map 有可能接口调用方法的入参是Map
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) parameterObject;
        // 遍历providerMethodArgumentNames 看map是否含有KEY依次返回
        sql = invokeProviderMethod(extractProviderMethodArguments(params, providerMethodArgumentNames));
      } else {
        throw new BuilderException("Error invoking SqlProvider method ("
                + providerType.getName() + "." + providerMethod.getName()
                + "). Cannot invoke a method that holds "
                + (bindParameterCount == 1 ? "named argument(@Param)" : "multiple arguments")
                + " using a specifying parameterObject. In this case, please specify a 'java.util.Map' object.");
      }
      Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();
      // 通过XMLLanguageDriver生成SqlSource对象（ProviderSqlSource相对就是个delegate）
      return languageDriver.createSqlSource(configuration, sql, parameterType);
    } catch (BuilderException e) {
      throw e;
    } catch (Exception e) {
      throw new BuilderException("Error invoking SqlProvider method ("
          + providerType.getName() + "." + providerMethod.getName()
          + ").  Cause: " + e, e);
    }
  }

  private Object[] extractProviderMethodArguments(Object parameterObject) {
    if (providerContext != null) {
      Object[] args = new Object[2];
      // 判断参数位置，providerContextIndex为0，parameterObject的位置为1，反之如此。
      args[providerContextIndex == 0 ? 1 : 0] = parameterObject;
      args[providerContextIndex] = providerContext;
      return args;
    } else {
      return new Object[] { parameterObject };
    }
  }

  private Object[] extractProviderMethodArguments(Map<String, Object> params, String[] argumentNames) {
    Object[] args = new Object[argumentNames.length];
    for (int i = 0; i < args.length; i++) {
      if (providerContextIndex != null && providerContextIndex == i) {
        args[i] = providerContext;
      } else {
        args[i] = params.get(argumentNames[i]);
      }
    }
    return args;
  }

  private String invokeProviderMethod(Object... args) throws Exception {
    Object targetObject = null;
    if (!Modifier.isStatic(providerMethod.getModifiers())) {
      targetObject = providerType.newInstance();
    }
    CharSequence sql = (CharSequence) providerMethod.invoke(targetObject, args);
    return sql != null ? sql.toString() : null;
  }

}
