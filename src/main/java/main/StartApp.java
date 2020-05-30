package main;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.netflix.rewrite.ast.Tr;
import com.netflix.rewrite.ast.Tr.ClassDecl;
import com.netflix.rewrite.ast.Tr.CompilationUnit;
import com.netflix.rewrite.ast.Tr.MethodDecl;
import com.netflix.rewrite.parse.OracleJdkParser;
import com.netflix.rewrite.parse.Parser;
import com.netflix.rewrite.refactor.Refactor;
import com.netflix.rewrite.refactor.RefactorVisitor;

import org.eclipse.jgit.api.Git;
import org.takes.facets.fork.FkMethods;
import org.takes.facets.fork.FkRegex;
import org.takes.facets.fork.TkFork;
import org.takes.http.Exit;
import org.takes.http.FtBasic;
import org.takes.rq.form.RqFormBase;
import org.takes.rs.RsWithStatus;

public class StartApp {

	static Parser parser = null;
	
    static Field opsField = null;
    
    static Class<?> refactorOp = null;
    static Constructor<?> refactorOpConstructor = null;

    static String gitDirectory = null;
    static Path srcDir = null;
    static List<Path> srcFiles = null;
    static List<Tr.CompilationUnit> compilationUnits = null;
    static Map<CompilationUnit,Path> compilationUnitToFilePath = null;
    
    static {
    	try {
	    	parser = new OracleJdkParser();
	    	opsField = Refactor.class.getDeclaredField("ops");
	    	refactorOp = Class.forName("com.netflix.rewrite.refactor.Refactor$RefactorOperation");
	    	refactorOpConstructor = refactorOp.getDeclaredConstructor(long.class,RefactorVisitor.class);
		    opsField.setAccessible(true);
		    refactorOpConstructor.setAccessible(true);
    	} catch(Throwable t) {
    		System.err.println("could not init");
    		System.exit(-1);
    	}
    }
    @SuppressWarnings({ "rawtypes", "unchecked" })
	public static void addCustomRefactorOperation(Refactor r,Tr.CompilationUnit targetCompilationUnit,RefactorVisitor<?> customVistor) throws Throwable {
        List ops = (List) opsField.get(r);
        Object refactorOpInstance = refactorOpConstructor.newInstance(targetCompilationUnit.getId(),customVistor);
        ops.add(refactorOpInstance);
    }
    public static void init(String gitDirectory) throws Throwable {
    	compilationUnitToFilePath = new Hashtable<Tr.CompilationUnit, Path>();
    	System.out.println("Trying to read git directory:");
    	System.out.println(gitDirectory);
    	System.out.println("Absolute Path:");
    	System.out.println(Paths.get(gitDirectory).toAbsolutePath());
    	StartApp.gitDirectory = gitDirectory;
    	try(Stream<Path> ifstr = Files.walk(Paths.get(gitDirectory), Integer.MAX_VALUE)) {
    		ifstr.filter(p->p.endsWith("src/main/java")).findFirst().ifPresent(p->{srcDir = p;});
    	}
    	
    	try(Stream<Path> ifstr = Files.walk(srcDir, Integer.MAX_VALUE)) {
    		srcFiles = ifstr.map(p->p.toAbsolutePath())
    				        .filter(p->!p.toFile().isDirectory())
    				        .collect(Collectors.toList());
    		
    		compilationUnits = parser.parse(srcFiles);
    		
    		for(int i=0;i<srcFiles.size();++i) {
    			compilationUnitToFilePath.put(compilationUnits.get(i), srcFiles.get(i));
    		}
    	}
    	//try to open git directory. will throw error when not possible
    	Git git = Git.open(new File(gitDirectory));
    }    
    public static void reset() {
    	srcDir = null;
    	srcFiles = null;
    	StartApp.gitDirectory = null;
    	compilationUnitToFilePath = null;
    }
    public static ClassDecl findClassInCompilationUnit(CompilationUnit cu,String simpleClassname){
    	return cu.getClasses().stream()
    			.filter(c->c.getName().print().trim().equals(simpleClassname))
    			.findFirst().get();
    }
    public static List<MethodDecl> findMethodsInCompilationUnit(CompilationUnit cu,String simpleClassname,String methodName){
    	return cu.getClasses().stream()
    			.filter(c->c.getName().print().trim().equals(simpleClassname))
    			.findFirst().get().methods()
    			.stream()
    			.filter(m->m.getName().print().trim().equals(methodName))
    			.collect(Collectors.toList());
    }
    public static List<MethodDecl> findMethodsInCompilationUnitByFqn(CompilationUnit cu,String fqnClassname,String methodName){
    	return cu.getClasses().stream()
    			.filter(c->c.getName().print().trim().equals(classFqnToSimpleName(fqnClassname)))
    			.findFirst().get().methods()
    			.stream()
    			.filter(m->m.getName().print().trim().equals(methodName))
    			.collect(Collectors.toList());
    }

    public static List<CompilationUnit> findCompilationUnitsForFullyQualifiedName(String fqn){ 
  		 return 
			compilationUnits.stream().filter(cu->
			!cu.getClasses().stream().filter(clazz->
				((cu.getPackageDecl().print().substring(7).trim())+"."+(clazz.getName().print().trim()))
				.equals(fqn)).collect(Collectors.toList()).isEmpty()
			).collect(Collectors.toList());
  	 }
    public static String classFqnToSimpleName(String fqn) {
    	if(fqn.contains(".")) {
    		return fqn.substring(fqn.lastIndexOf(".")+1);
    	} else {
    		return fqn;
    	}
    }
    public static String relativFilePath(CompilationUnit cu) {
    	return compilationUnitToFilePath.get(cu).toAbsolutePath().toString().substring(gitDirectory.length());
    }
    public static void barf(CompilationUnit dest,String content) throws FileNotFoundException {
    	try (PrintWriter out = new PrintWriter(new FileOutputStream(compilationUnitToFilePath.get(dest).toFile(),false))) {
    	    out.print(content);
    	}
    }
    public static void barf(String dest,String content) throws FileNotFoundException {
    	try (PrintWriter out = new PrintWriter(new FileOutputStream(new File(dest),false))) {
    	    out.print(content);
    	}
    }
    public static void moveMethods(String fromClassfqn, String toClassfqn, String methodName) throws Throwable {
    	String simpleFromClassname = classFqnToSimpleName(fromClassfqn);
    	List<CompilationUnit> fromCandidates = findCompilationUnitsForFullyQualifiedName(fromClassfqn);
    	List<CompilationUnit> toCandidates = findCompilationUnitsForFullyQualifiedName(toClassfqn);
    	if(fromCandidates.size() != 1 || toCandidates.size() != 1) {
    		throw new Exception("ambiguous class");
    	}
    	CompilationUnit fromCu = fromCandidates.get(0);
    	CompilationUnit toCu = toCandidates.get(0);
    	List<MethodDecl> methods = findMethodsInCompilationUnit(fromCu, simpleFromClassname, methodName);
    	if(methods.isEmpty()) {
    		throw new Exception("could not find method in class");
    	}

    	Refactor refactorFrom = fromCu.refactor();
    	Refactor refactorTo = toCu.refactor();
    	
    	RemoveMethods rm = new RemoveMethods(fromCu, simpleFromClassname, methodName);
    	AddMethods add = new AddMethods(methods);

    	addCustomRefactorOperation(refactorFrom, fromCu, rm);
    	addCustomRefactorOperation(refactorTo, toCu, add);
    	

    	Git git = Git.open(new File(gitDirectory));
    	String currentBranch = git.getRepository().getFullBranch();
    	String branchName = "move_"+methodName+"_from_"+fromClassfqn+"_to_"+toClassfqn;
    	git.checkout().setCreateBranch(true).setName(branchName).call();
    	
    	barf(fromCu,refactorFrom.fix().print());
    	git.add().addFilepattern(relativFilePath(fromCu)).call();
    	git.commit().setMessage("delete "+methodName).call();
    	
    	barf(toCu,refactorTo.fix().print());
    	git.add().addFilepattern(relativFilePath(toCu)).call();
    	git.commit().setMessage("insert "+methodName).call();
    	
    	git.checkout().setName(currentBranch).call();
    	
    }
    public static void mergeClasses(String fromClassfqn, String toClassfqn) throws Throwable {
    	String simpleFromClassname = classFqnToSimpleName(fromClassfqn);
    	List<CompilationUnit> fromCandidates = findCompilationUnitsForFullyQualifiedName(fromClassfqn);
    	List<CompilationUnit> toCandidates = findCompilationUnitsForFullyQualifiedName(toClassfqn);
    	if(fromCandidates.size() != 1 || toCandidates.size() != 1) {
    		throw new Exception("ambiguous class");
    	}
    	CompilationUnit fromCu = fromCandidates.get(0);
    	CompilationUnit toCu = toCandidates.get(0);

    	Refactor refactorTo = toCu.refactor();
    	
    	MergeClass merge = new MergeClass(fromCu.getClasses().stream().filter(c->c.getName().printTrimmed().equals(simpleFromClassname)).findFirst().get());

    	addCustomRefactorOperation(refactorTo, toCu, merge);
    	

    	Git git = Git.open(new File(gitDirectory));
    	String currentBranch = git.getRepository().getFullBranch();
    	String branchName = "merge_"+fromClassfqn+"_into_"+toClassfqn;
    	git.checkout().setCreateBranch(true).setName(branchName).call();
    	
    	new File(compilationUnitToFilePath.get(fromCu).toString()).delete();
    	git.rm().addFilepattern(relativFilePath(fromCu)).call();
    	git.commit().setMessage("delete "+fromClassfqn).call();
    	
    	barf(toCu,refactorTo.fix().print());
    	git.add().addFilepattern(relativFilePath(toCu)).call();
    	git.commit().setMessage("insert "+fromClassfqn).call();
    	
    	git.checkout().setName(currentBranch).call();
    	
    }
    public static void splitClasses(String classfqn, List<String> methodsInOldClass) throws Throwable {
    	String simpleFromClassname = classFqnToSimpleName(classfqn);
    	List<CompilationUnit> fromCandidates = findCompilationUnitsForFullyQualifiedName(classfqn);
    	if(fromCandidates.size() != 1) {
    		throw new Exception("ambiguous class");
    	}
    	CompilationUnit cu = fromCandidates.get(0);

    	Refactor refactorFrom = cu.refactor();
    	Refactor refactorTo = cu.refactor();
    	
    	List<MethodDecl> methodsInFrom = methodsInOldClass.stream().map(m->findMethodsInCompilationUnit(cu, simpleFromClassname,m))
    														 .reduce(new ArrayList<MethodDecl>(),(acc,m)->{acc.addAll(m);return acc;});
    	List<MethodDecl> methodsInTo = findClassInCompilationUnit(cu,simpleFromClassname).methods();
    	methodsInTo.removeAll(methodsInFrom);
    	
    	
    	methodsInFrom.forEach(md->{
			try {
				addCustomRefactorOperation(refactorFrom, cu, new RemoveMethods(cu, simpleFromClassname, md.getName().printTrimmed()));
			} catch (Throwable e) {
				e.printStackTrace();
			}
		});
    	methodsInTo.forEach(md->{
			try {
				addCustomRefactorOperation(refactorTo, cu, new RemoveMethods(cu, simpleFromClassname, md.getName().printTrimmed()));
			} catch (Throwable e) {
				e.printStackTrace();
			}
		});
    	
    	
    	Git git = Git.open(new File(gitDirectory));
    	String currentBranch = git.getRepository().getFullBranch();
    	String branchName = "split_"+classfqn;
    	git.checkout().setCreateBranch(true).setName(branchName).call();
    	
    	new File(compilationUnitToFilePath.get(cu).toString()+"split.java").createNewFile();
    	git.add().addFilepattern(relativFilePath(cu)+"split.java").call();
    	git.commit().setMessage("copy "+classfqn).call();

    	barf(compilationUnitToFilePath.get(cu).toString(),refactorFrom.fix().print());
    	git.add().addFilepattern(relativFilePath(cu)).call();
    	git.commit().setMessage("delete half from original class").call();
    	

    	barf(compilationUnitToFilePath.get(cu).toString()+"split.java",refactorTo.fix().print());
    	git.add().addFilepattern(relativFilePath(cu)+"split.java").call();
    	git.commit().setMessage("delete half from new class").call();
    	
    	git.checkout().setName(currentBranch).call();
    	
    }

    public static void main(String[] args) throws Throwable {
    	
    	new FtBasic(
	    		new TkFork(
	    				
	    				new FkRegex("/refactor/init", 
	    						new TkFork(
	    								new FkMethods("POST", req->{
	    									RqFormBase formdata = new RqFormBase(req);
	    									String formDataGitDirectory = formdata.param("gitDirectory").iterator().next();
	    									try{
	    										init(formDataGitDirectory);
	    										return new RsWithStatus(200);
	    									} catch(Throwable t) {
												System.err.println(t.getMessage());
                                                t.printStackTrace();
	    										return new RsWithStatus(500);
	    									}
	    									}))),
	    				new FkRegex("/refactor/move_methods", 
	    						new TkFork(
	    								new FkMethods("POST", req->{
	    									RqFormBase formdata = new RqFormBase(req);

	    									String fromClassfqn = formdata.param("fromClassfqn").iterator().next();
	    									String toClassfqn = formdata.param("toClassfqn").iterator().next();
	    									String methodName = formdata.param("methodName").iterator().next();
	    									try {
	    										moveMethods(fromClassfqn, toClassfqn, methodName);
												return new RsWithStatus(200);
											} catch (Throwable e) {
												System.err.println(e.getMessage());
                                                e.printStackTrace();
												return new RsWithStatus(500);
											}}))),
	    				new FkRegex("/refactor/merge_classes", 
	    						new TkFork(
	    								new FkMethods("POST", req->{
	    									RqFormBase formdata = new RqFormBase(req);

	    									String fromClassfqn = formdata.param("fromClassfqn").iterator().next();
	    									String toClassfqn = formdata.param("toClassfqn").iterator().next();
	    									try {
	    										mergeClasses(fromClassfqn, toClassfqn);
												return new RsWithStatus(200);
											} catch (Throwable e) {
												System.err.println(e.getMessage());
                                                e.printStackTrace();
												return new RsWithStatus(500);
											}}))),
	    				new FkRegex("/refactor/split_classes", 
	    						new TkFork(
	    								new FkMethods("POST", req->{
	    									RqFormBase formdata = new RqFormBase(req);

	    									String classfqn = formdata.param("classfqn").iterator().next();
	    									List<String> selectedMethods = new ArrayList<>();
	    									formdata.param("method").forEach(selectedMethods::add);
	    									try {
	    										splitClasses(classfqn, selectedMethods);
												return new RsWithStatus(200);
											} catch (Throwable e) {
												System.err.println(e.getMessage());
                                                e.printStackTrace();
												return new RsWithStatus(500);
											}}))),
	    				new FkRegex("/refactor/reset", 
	    						new TkFork(
	    								new FkMethods("POST", req->{
	    									reset();
	    									return new RsWithStatus(200);})))
	    				), 8078
	    	    ).start(Exit.NEVER);

        
    }
    
}

