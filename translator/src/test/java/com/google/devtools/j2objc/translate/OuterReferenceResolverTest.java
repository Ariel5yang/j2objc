/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.j2objc.translate;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.devtools.j2objc.GenerationTest;
import com.google.devtools.j2objc.ast.AnonymousClassDeclaration;
import com.google.devtools.j2objc.ast.ClassInstanceCreation;
import com.google.devtools.j2objc.ast.CompilationUnit;
import com.google.devtools.j2objc.ast.InfixExpression;
import com.google.devtools.j2objc.ast.MethodInvocation;
import com.google.devtools.j2objc.ast.PostfixExpression;
import com.google.devtools.j2objc.ast.TreeNode;
import com.google.devtools.j2objc.ast.TreeNode.Kind;
import com.google.devtools.j2objc.ast.TreeVisitor;
import com.google.devtools.j2objc.ast.TypeDeclaration;
import com.google.devtools.j2objc.util.ElementUtil;
import java.io.IOException;
import java.util.List;
import javax.lang.model.element.VariableElement;

/**
 * Unit tests for {@link OuterReferenceResolver}.
 *
 * @author Keith Stanger
 */
public class OuterReferenceResolverTest extends GenerationTest {

  private OuterReferenceResolver outerResolver;
  private ListMultimap<Kind, TreeNode> nodesByType = ArrayListMultimap.create();

  @Override
  protected void setUp() throws IOException {
    super.setUp();
    outerResolver = new OuterReferenceResolver();
  }

  @Override
  protected void tearDown() throws Exception {
    nodesByType.clear();
  }

  public void testOuterVarAccess() {
    resolveSource("Test", "class Test { int i; class Inner { void test() { i++; } } }");

    TypeDeclaration innerNode = (TypeDeclaration) nodesByType.get(Kind.TYPE_DECLARATION).get(1);
    assertTrue(outerResolver.needsOuterReference(innerNode.getTypeElement()));

    PostfixExpression increment =
        (PostfixExpression) nodesByType.get(Kind.POSTFIX_EXPRESSION).get(0);
    List<VariableElement> path = outerResolver.getPath(increment.getOperand());
    assertNotNull(path);
    assertEquals(2, path.size());
    assertEquals("Test", path.get(0).asType().toString());
  }

  public void testInheritedOuterMethod() {
    resolveSource("Test",
        "class Test { class A { void foo() {} } class B extends A { "
        + "class Inner { void test() { foo(); } } } }");

    TypeDeclaration aNode = (TypeDeclaration) nodesByType.get(Kind.TYPE_DECLARATION).get(1);
    TypeDeclaration bNode = (TypeDeclaration) nodesByType.get(Kind.TYPE_DECLARATION).get(2);
    TypeDeclaration innerNode = (TypeDeclaration) nodesByType.get(Kind.TYPE_DECLARATION).get(3);
    assertFalse(outerResolver.needsOuterReference(aNode.getTypeElement()));
    assertFalse(outerResolver.needsOuterReference(bNode.getTypeElement()));
    assertTrue(outerResolver.needsOuterReference(innerNode.getTypeElement()));

    // B will need an outer reference to Test so it can initialize its
    // superclass A.
    List<VariableElement> bPath = outerResolver.getPath(bNode);
    assertNotNull(bPath);
    assertEquals(1, bPath.size());
    assertEquals("outer$", ElementUtil.getName(bPath.get(0)));

    // foo() call will need to get to B's scope to call the inherited method.
    MethodInvocation fooCall = (MethodInvocation) nodesByType.get(Kind.METHOD_INVOCATION).get(0);
    List<VariableElement> fooPath = outerResolver.getPath(fooCall);
    assertNotNull(fooPath);
    assertEquals(1, fooPath.size());
    assertEquals("B", fooPath.get(0).asType().toString());
  }

  public void testCapturedLocalVariable() {
    resolveSource("Test",
        "class Test { void test(final int i) { Runnable r = new Runnable() { "
        + "public void run() { int i2 = i + 1; } }; } }");

    AnonymousClassDeclaration runnableNode =
        (AnonymousClassDeclaration) nodesByType.get(Kind.ANONYMOUS_CLASS_DECLARATION).get(0);
    assertFalse(outerResolver.needsOuterReference(runnableNode.getTypeElement()));
    List<VariableElement> innerFields = outerResolver.getInnerFields(runnableNode.getTypeElement());
    assertEquals(1, innerFields.size());
    assertEquals("val$i", innerFields.get(0).getSimpleName().toString());
    ClassInstanceCreation creationNode =
        (ClassInstanceCreation) nodesByType.get(Kind.CLASS_INSTANCE_CREATION).get(0);
    List<List<VariableElement>> captureArgPaths = outerResolver.getCaptureArgPaths(creationNode);
    assertEquals(1, captureArgPaths.size());
    assertEquals(1, captureArgPaths.get(0).size());
    assertEquals("i", captureArgPaths.get(0).get(0).getSimpleName().toString());

    InfixExpression addition = (InfixExpression) nodesByType.get(Kind.INFIX_EXPRESSION).get(0);
    List<VariableElement> iPath = outerResolver.getPath(addition.getOperands().get(0));
    assertNotNull(iPath);
    assertEquals(1, iPath.size());
    assertEquals("val$i", iPath.get(0).getSimpleName().toString());
  }

  public void testCapturedWeakLocalVariable() {
    resolveSource("Test",
        "import com.google.j2objc.annotations.Weak;"
        + "class Test { void test(@Weak final int i) { Runnable r = new Runnable() { "
        + "public void run() { int i2 = i + 1; } }; } }");

    AnonymousClassDeclaration runnableNode =
        (AnonymousClassDeclaration) nodesByType.get(Kind.ANONYMOUS_CLASS_DECLARATION).get(0);
    List<VariableElement> innerFields = outerResolver.getInnerFields(runnableNode.getTypeElement());
    assertEquals(1, innerFields.size());
    assertTrue(ElementUtil.isWeakReference(innerFields.get(0)));
  }

  public void testAnonymousClassInheritsLocalClassInStaticMethod() {
    resolveSource("Test",
        "class Test { static void test() { class LocalClass {}; new LocalClass() {}; } }");

    AnonymousClassDeclaration decl =
        (AnonymousClassDeclaration) nodesByType.get(Kind.ANONYMOUS_CLASS_DECLARATION).get(0);
    assertFalse(outerResolver.needsOuterParam(decl.getTypeElement()));
  }

  public void testAnonymousClassCreatesLocalClassWithCaptures() {
    resolveSource("Test",
        "class Test { Runnable test(final Object o) { "
        + "class Local { public void foo() { o.toString(); } } "
        + "return new Runnable() { public void run() { new Local(); } }; } }");

    AnonymousClassDeclaration runnableNode =
        (AnonymousClassDeclaration) nodesByType.get(Kind.ANONYMOUS_CLASS_DECLARATION).get(0);
    List<VariableElement> innerFields = outerResolver.getInnerFields(runnableNode.getTypeElement());
    assertEquals(1, innerFields.size());
    assertEquals("val$o", innerFields.get(0).getSimpleName().toString());
    ClassInstanceCreation creationNode =
        (ClassInstanceCreation) nodesByType.get(Kind.CLASS_INSTANCE_CREATION).get(1);
    List<List<VariableElement>> captureArgPaths = outerResolver.getCaptureArgPaths(creationNode);
    assertEquals(1, captureArgPaths.size());
    assertEquals(1, captureArgPaths.get(0).size());
    assertEquals("val$o", captureArgPaths.get(0).get(0).getSimpleName().toString());
  }

  public void testNoOuterFieldWhenSuperConstructorIsQualified() {
    resolveSource("Test",
        "class Test { class B { class Inner {} } class A { class Inner extends B.Inner { "
        + " Inner(B b) { b.super(); } } } }");
    List<TreeNode> typeNodes = nodesByType.get(Kind.TYPE_DECLARATION);
    assertEquals(5, typeNodes.size());
    for (TreeNode typeNode : typeNodes) {
      assertNull(outerResolver.getOuterField(((TypeDeclaration) typeNode).getTypeElement()));
    }
  }

  public void testNestedLocalClassesWithNestedCreations() {
    // This test is particularly tricky for OuterReferenceResolver because A captures variable i,
    // but that is not known until after A's creation. A's creation occurs within B, which requires
    // B to have an outer field in order to access A's capturing field for i. B's creation therefore
    // requires the outer field to be passed as an outer argument.
    // Because of the cascading effects of the statements in this test and the order in which they
    // occur, we would need to do three passes over the code to resolve B's creation successfuly.
    resolveSource("Test",
        "class Test { void test(int i) { class A { "
        + "void foo() { class B { void bar() { new B(); new A(); } } } "
        + "int other() { return i; } } } }");
    ClassInstanceCreation bCreate =
        (ClassInstanceCreation) nodesByType.get(Kind.CLASS_INSTANCE_CREATION).get(0);
    List<VariableElement> path = outerResolver.getPath(bCreate);
    assertNotNull(path);
    assertEquals(1, path.size());
    assertEquals("this$0", ElementUtil.getName(path.get(0)));
  }

  private void resolveSource(String name, String source) {
    CompilationUnit unit = compileType(name, source);
    outerResolver.run(unit);
    findTypeDeclarations(unit);
  }

  private void findTypeDeclarations(CompilationUnit unit) {
    unit.accept(new TreeVisitor() {
      @Override
      public boolean preVisit(TreeNode node) {
        nodesByType.put(node.getKind(), node);
        return true;
      }
    });
  }
}
