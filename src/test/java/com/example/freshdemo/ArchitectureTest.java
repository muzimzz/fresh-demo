package com.example.freshdemo;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.transaction.annotation.Transactional;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/*
 * fm-backend(com.freshmarket)의 src/test/java/com/freshmarket/ArchitectureTest.java를
 * com.example.freshdemo 기준으로 옮겨온 검증용 사본이다 — 이식 전에 fresh-demo v1이 LG-fm
 * domain-package-boundary 규칙을 실제로 지키는지 확인하려고 임시로 넣었다. 이식 시점엔 이
 * 파일을 지우고 fm-backend 쪽 원본을 그대로 쓰면 된다.
 *
 * fm-backend 원본의 "도메인은_아래로만_부른다"(L0/L1/L2 계층, product/stock/coupon/cart/order 등)
 * 규칙은 뺐다 — fresh-demo엔 member/admin/address/membergrade 4개 도메인뿐이라 그 계층 구조가
 * 아직 적용 대상이 아니다. 나머지 도메인-무관 규칙(경계/계층방향/이름/순환/트랜잭션/엔티티위치/
 * common순수성)은 전부 그대로 가져왔다.
 *
 * JaCoCo 100% 커버리지 게이트는 의도적으로 안 가져왔다 — 서비스 클래스에 대한 단위 테스트가
 * 아직 없어서 그걸 같이 켜면 이 검증과 무관한 이유로 바로 실패한다. ArchUnit 규칙 통과 여부만
 * 먼저 보고, 커버리지는 나중에 별도로 다룬다.
 */
@AnalyzeClasses(packages = "com.example.freshdemo", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String BASE = "com.example.freshdemo";

    /*
     * 다른 도메인의 domain 패키지에 손대지 못하게 막는다.
     * 도메인끼리는 루트에 공개한 Api 인터페이스와 DTO로만 오간다.
     */
    @ArchTest
    static final ArchRule 도메인_내부는_다른_도메인에_닫혀_있다 = slices()
            .matching(BASE + ".(*)..")
            .namingSlices("$1")
            .should().notDependOnEachOther()
            .ignoreDependency(
                    resideInAPackage(BASE + ".."),
                    resideInAnyPackage(
                            BASE + ".*",            // 도메인 루트만 허용
                            BASE + ".common..",
                            BASE + ".config.."));

    /*
     * 도메인 안에서 위에서 아래로만 흐르게 한다.
     */
    @ArchTest
    static final ArchRule 계층은_아래로만_흐른다 = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("Controller").definedBy("..domain.controller..")
            .layer("Service").definedBy("..domain.service..")
            .layer("Repository").definedBy("..domain.repository..")
            .layer("Client").definedBy("..domain.client..")
            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
            .whereLayer("Client").mayOnlyBeAccessedByLayers("Service");

    /*
     * 계층 패키지의 클래스는 그 계층 이름을 접미사로 갖는다 (DPB-4-10).
     */
    @ArchTest
    static final ArchRule 컨트롤러_이름 = layerSuffix("controller", "Controller");

    @ArchTest
    static final ArchRule 서비스_이름 = layerSuffix("service", "Service");

    @ArchTest
    static final ArchRule 레포지토리_이름 = layerSuffix("repository", "Repository");

    private static ArchRule layerSuffix(String layer, String suffix) {
        return classes()
                .that().resideInAPackage("..domain." + layer + "..")
                .and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith(suffix)
                .allowEmptyShould(true);
    }

    // 순환 의존은 어느 층에서든 막는다
    @ArchTest
    static final ArchRule 순환_의존이_없다 = slices()
            .matching(BASE + ".(*)..")
            .should().beFreeOfCycles();

    /*
     * API 구현체와 외부 연동 클래스에 트랜잭션을 걸지 않는다 (DPB-3-04, DPB-7-01).
     */
    @ArchTest
    static final ArchRule ApiImpl_에_트랜잭션이_없다 = noClasses()
            .that().haveSimpleNameEndingWith("ApiImpl")
            .should().beAnnotatedWith(Transactional.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule ApiImpl_메서드에_트랜잭션이_없다 = noMethods()
            .that().areDeclaredInClassesThat().haveSimpleNameEndingWith("ApiImpl")
            .should().beAnnotatedWith(Transactional.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule client_에_트랜잭션이_없다 = noClasses()
            .that().resideInAPackage("..domain.client..")
            .should().beAnnotatedWith(Transactional.class)
            .allowEmptyShould(true);

    /*
     * 엔티티는 도메인 안에만 둔다 (DPB-2-01).
     */
    @ArchTest
    static final ArchRule 엔티티는_도메인_안에만_있다 = classes()
            .that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage("..domain.entity..")
            .allowEmptyShould(true);

    /*
     * common은 도메인을 모른다 (DPB-5-03).
     */
    @ArchTest
    static final ArchRule common_은_도메인을_모른다 = noClasses()
            .that().resideInAnyPackage(BASE + ".common..", BASE + ".config..")
            .should().dependOnClassesThat()
            .resideInAPackage(BASE + ".*.domain..")
            .allowEmptyShould(true);
}
