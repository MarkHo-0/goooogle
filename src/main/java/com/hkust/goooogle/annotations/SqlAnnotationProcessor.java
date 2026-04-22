package com.hkust.goooogle.annotations;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.nio.charset.StandardCharsets;

@Component
public class SqlAnnotationProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        ReflectionUtils.doWithFields(bean.getClass(), field -> {
            LoadSql annotation = field.getAnnotation(LoadSql.class);
            if (annotation != null) {
                try {
                    String sqlPath = annotation.value();
                    String sqlContent = new String(new ClassPathResource(sqlPath).getInputStream().readAllBytes(), StandardCharsets.UTF_8);

                    ReflectionUtils.makeAccessible(field);
                    field.set(bean, sqlContent);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to inject SQL file: " + annotation.value(), e);
                }
            }
        });
        return bean;
    }
}
