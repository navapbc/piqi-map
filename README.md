# piqi-map
The [Patient Information Quality Improvement (PIQI)](https://build.fhir.org/ig/HL7/piqi/index.html) Framework is an evolving standard that wants to serve as a vehicle for promoting trust in the quality and clinical relevance of the healthcare data that transits our systems.

This project exists in part to promote engagement and accelerate adoption and education by providing an open source library that can be used as an approach for mapping data to the [PIQI data model](https://build.fhir.org/ig/HL7/piqi/piqi_framework.html#piqi-data-models).  

This project relies on the [HAPI-FHIR](https://github.com/jamesagnew/hapi-fhir) and [piqi-model](https://github.com/navapbc/piqi-model) libraries.

## Notice
The PIQI model is in early days.  One can easily imagine that the model will evolve as time goes on and it will be necessary to be able to map between multiple PIQI and FHIR versions.

It should also be noted that any mapping library needs to be validated for clinical accuracy.  This library is currently as immature as the PIQI model and has not passed a rigorous validation.

## How to Use

If you want to utilize this library in your project, simply clone it to your local machine, build it, and publish to the maven local repository.

```
./gradlew clean publishToMavenLocal
```

Then you may include as a dependency in your project.  For example, in Gradle:

```
...
dependencies {
    implementation("com.navapbc.piqi:piqi-map:0.2.0")
...
```

Then you might create a factory that provides you a mapper given FHIR version and PIQI class.  Alternatively, you might do something like this in Spring.  Create a Bean containing the mappers:

```
@Configuration
@Slf4j
public class PiqiEvaluatorConfig {

    @Bean
    public Set<PiqiBaseMapper> piqiMappers() {
        Set<PiqiBaseMapper> mappers = new HashSet<>();
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(PiqiBaseMapper.class));
        Set<org.springframework.beans.factory.config.BeanDefinition> beanDefinitions =
                scanner.findCandidateComponents("com.navapbc.piqi.map");
        for (org.springframework.beans.factory.config.BeanDefinition beanDefinition : beanDefinitions) {
            try {
                Class<?> subclass = Class.forName(beanDefinition.getBeanClassName());
                log.info("Found subclass=[{}]", subclass.getName());
                Object instance = subclass.getDeclaredConstructor().newInstance();
                mappers.add((PiqiBaseMapper) instance);
            } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException |
                     InvocationTargetException e) {
                log.warn("Couldn't load class for mapper.", e);
            }
        }
        return mappers;
    }

}
```

Then you could inject the bean into your Spring component and have a method that retrieves the mapper as needed:

```
    private PiqiBaseMapper getMapper(FhirVersionEnum fhirVersion, Class<?> piqiClass) throws IOException {
        PiqiBaseMapper mapper = null;
        for (PiqiBaseMapper piqiBaseMapper : mappers) {
            if (piqiBaseMapper.isFhirVersion(fhirVersion) && piqiBaseMapper.isMappingClassFor(piqiClass)) {
                mapper = piqiBaseMapper;
                break;
            }
        }
        return mapper;
    }
```







