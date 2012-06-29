package org.hoisted
package lib

import net.liftweb._
import builtin.snippet._
import common._
import http._
import js.{JsCmds, JsCmd, JE}
import js.JsCmds.Script
import util._
import Helpers._
import java.util.Locale
import java.io.{FileWriter, FileOutputStream, FileInputStream, File}
import org.eclipse.jgit.api.Git
import xml._
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}


object VeryTesty {
  def apply() = RunHoisted(new File("/Users/dpp/tmp/cms_site/site/"), new File("/Users/dpp/tmp/outfrog"))
}

/**
 * This singleton will take a directory, find all the files in the directory
 * and then generate a static site in the output directory and return metadata about
 * the transformation
 */

object RunHoisted extends HoistedRenderer

object CurrentFile extends ThreadGlobal[ParsedFile]

object PostPageTransforms extends TransientRequestVar[Vector[NodeSeq => NodeSeq]](Vector())

trait HoistedRenderer {
  @scala.annotation.tailrec
  private def seekInDir(in: File): File = {
    val all = in.listFiles().filter(!_.getName.startsWith(".")).filterNot(_.getName.toLowerCase.startsWith("readme"))
    if (all.length > 1 || all.length == 0 || !all(0).isDirectory) in else seekInDir(all(0))
  }

  def apply(_inDir: File, outDir: File, environment: EnvironmentManager = new DefaultEnvironmentManager): Box[HoistedTransformMetaData] = {
    HoistedEnvironmentManager.doWith(environment) {
      val inDir = seekInDir(_inDir)
      for {
        deleteAll <- tryo(deleteAll(outDir))
        theDir <- tryo(outDir.mkdirs())
        allFiles <- tryo(allFiles(inDir, f => f.exists() && !f.getName.startsWith(".") && f.getName.toLowerCase != "readme" &&
          f.getName.toLowerCase != "readme.md"))
        fileInfo <- tryo(allFiles.map(fileInfo(inDir)))
        _parsedFiles = (fileInfo: List[FileInfo]).flatMap(ParsedFile.apply _).filter(HoistedEnvironmentManager.value.isValid)
        parsedFiles = ensureTemplates(_parsedFiles)

        _ = HoistedEnvironmentManager.value.pages = parsedFiles

        fileMap = byName(parsedFiles)
        templates = createTemplateLookup(parsedFiles)
        menu = HoistedEnvironmentManager.value.computeMenuItems(parsedFiles)
        _ = HoistedEnvironmentManager.value.menuEntries = menu

        posts = HoistedEnvironmentManager.value.computePosts(parsedFiles)
        _ = HoistedEnvironmentManager.value.blogPosts = posts

        transformedFiles = parsedFiles.map(f => runTemplater(f, templates))

        done <- tryo(writeFiles(transformedFiles, inDir, outDir))
      } yield HoistedTransformMetaData()
    }
  }

  def ensureTemplates(in: List[ParsedFile]): List[ParsedFile] =
    if (HoistedEnvironmentManager.value.needsTemplates(in)) {
      val name = HoistedEnvironmentManager.value.computeTemplateURL()
      HoistedEnvironmentManager.value.loadTemplates(this, name, in)
    } else in

  def dropSuffix(in: String): String = {
    if (in.toLowerCase.endsWith(".cms.xml")) {
      in.substring(0, in.length - 8)
    } else in.lastIndexOf(".") match {
      case x if x < 0 => in
      case x => in.substring(0, x)
    }
  }

  def captureSuffix(in: String): String = {
    if (in.toLowerCase.endsWith(".cms.xml")) {
      "cms.xml"
    } else in.lastIndexOf(".") match {
      case x if x < 0 => ""
      case x => in.substring(x + 1)
    }
  }


  def writeFiles(toWrite: Seq[ParsedFile], inDir: File, outDir: File): Unit = {
    val bufLen = 4096
    val buffer = new Array[Byte](bufLen)

    def translate(source: String): File = {
      new File(outDir.getAbsolutePath + source)
    }

    def outputFile(m: ParsedFile): String = HoistedEnvironmentManager.value.computeOutputFileName(m)


    def calcFile(pf: ParsedFile with HasMetaData): File = translate(pf match {
      case XmlFile(f, _, _, _, _) => outputFile(pf)
      case HtmlFile(f, _, _, _) => outputFile(pf)
      case MarkdownFile(f, _, _, _) => outputFile(pf)
      case f => f.fileInfo.relPath
    })

    def copy(from: File, to: File) {
      val in = new FileInputStream(from)
      try {
        to.getParentFile().mkdirs()
        val out = new FileOutputStream(to)
        try {
          var len = 0
          while ( {
            len = in.read(buffer, 0, bufLen);
            len >= 0
          }) {
            if (len > 0) out.write(buffer, 0, len)
          }
        } finally {
          out.close()
        }
      } finally {
        in.close()
      }
    }

    toWrite.foreach {
      case pf: ParsedFile with HasHtml with HasMetaData if HoistedEnvironmentManager.value.isHtml(pf) => if (shouldEmitFile(pf)) {
        val where = calcFile(pf)
        where.getParentFile.mkdirs()
        val out = new FileWriter(where)
        out.write("<!DOCTYPE html>\n")
        try {
          Html5.write(pf.html.collect {
            case e: Elem => e
          }.headOption getOrElse <html/>, out, false, true)
        } finally {
          out.close()
        }
        where.setLastModified(HoistedEnvironmentManager.value.computeDate(pf).getMillis)
      }

      case pf: ParsedFile with HasHtml with HasMetaData => if (shouldEmitFile(pf)) {
        val where = calcFile(pf)
        where.getParentFile.mkdirs()
        val out = new FileWriter(where)

        try {
          out.write(pf.html.text)
        } finally {
          out.close()
        }
        where.setLastModified(HoistedEnvironmentManager.value.computeDate(pf).getMillis)
      }

      case f if (shouldEmitFile(f)) =>
        val where = translate(f.fileInfo.relPath)
        copy(f.fileInfo.file, where)
        where.setLastModified(HoistedEnvironmentManager.value.computeDate(f).getMillis)
      case x =>
    }
  }

  def shouldEmitFile(pf: ParsedFile): Boolean = HoistedEnvironmentManager.value.shouldWriteHtmlFile(pf)

  type TemplateLookup = PartialFunction[(List[String], String), ParsedFile]

  def createTemplateLookup(in: Seq[ParsedFile]): TemplateLookup = {
    def makeName(f: ParsedFile): (List[String], String) = {
      f match {
        case h: HasHtml => (dropSuffix(f.fileInfo.relPath).roboSplit("/"), "html")
        case f => (dropSuffix(f.fileInfo.relPath).roboSplit("/"), captureSuffix(f.fileInfo.relPath))
      }
    }
    Map(in.map(f => (makeName(f), f)): _*)
  }


  def blogPosts: NodeSeq => NodeSeq = {
    val posts = HoistedEnvironmentManager.value.blogPosts.take(S.attr("max").flatMap(Helpers.asInt _) openOr Integer.MAX_VALUE)

    val m = HoistedEnvironmentManager.value

    val f = DateTimeFormat.shortDate()



    ("data-post=item" #> posts.map {
      p =>
        val (short, more) = m.computeShortContent(p)

        "data-post=link [href]" #> m.computeLink(p) &
          "data-post=link *" #> m.computeLinkText(p) &
          "data-post=shortcontent" #> short &
          (if (more) ("data-post=more" #> ("a [href]" #> m.computeLink(p))) else "data-post=more" #> (Empty: Box[String])) &
          "data-post=content" #> m.computeContent(p) &
          "data-post=date *" #> f.print(m.computeDate(p))
    } andThen "* [data-post]" #> (Empty: Box[String]))
  }

  def xform: NodeSeq => NodeSeq = ns => {
    for {
      css <- (ns.collect {
        case e: Elem => e
      }.flatMap(_.attribute("data-css")).headOption.map(_.text): Option[String])
      thing: NodeSeq = S.attr("kids").
        flatMap(Helpers.asBoolean).
        filter(a => a).map(ignore => ns.collect {
        case e: Elem => e
      }.flatMap(_.child): NodeSeq) openOr
        (("* [data-css]" #> (None: Option[String])).apply(ns))
      xf <- (Helpers.tryo(css #> thing): Box[NodeSeq => NodeSeq])
    } {
      PostPageTransforms.set(PostPageTransforms.get :+ xf)
    }

    NodeSeq.Empty
  }

  def env = HoistedEnvironmentManager.value

  def doA: NodeSeq => NodeSeq = ns => {
    (for {
      name <- S.attr("name")
      it <- env.findByTag("name", Full(name)).headOption
    } yield <a href={env.computeLink(it)}>{ns}</a>) openOr ns
  }

  def doXmenu: NodeSeq => NodeSeq = ns => {
    val activeClass = S.attr("active_class") openOr "active"
    val menus: List[ParsedFile] = HoistedEnvironmentManager.value.findByTag("menu", Empty)
    val sorted = menus.sortWith((a, b) =>
      (a.findData(OrderKey).flatMap(_.asInt) openOr 0) <
        (b.findData(OrderKey).flatMap(_.asInt) openOr 0)
    )

    if ((ns \\ "item").filter {
      case e: Elem => e.prefix == "menu"
      case _ => false
    }.isDefined) {
      sorted.flatMap(fr =>
        bind("menu", ns, "item" -> env.computeLinkText(fr),
          FuncAttrOptionBindParam("class", (value: NodeSeq) =>
            if (fr eq CurrentFile.value) Some(value) else None, "class"),
          AttrBindParam("href", env.computeLink(fr), "href"))
      )
    } else {
      ("*" #> sorted.map(fr =>
        "a [href]" #> env.computeLink(fr) &
          "a *" #> env.computeLinkText(fr) andThen
          (if (fr eq CurrentFile.value)
            ("* [class+]" #> activeClass)
          else
            PassThru))).apply(ns)
    }
  }

  def doTitle(ns: NodeSeq): NodeSeq = <head>
    <title>
      {ns.text}
    </title>
  </head>

  def doBind: NodeSeq => NodeSeq = ignore => {
    S.attr("name").map(name => <div id={name}></div>) openOr NodeSeq.Empty
  }

  def doSubs(in: NodeSeq): NodeSeq = {
      val info = for {
        tpe <- S.attr("type").toList
        max = S.attr("max").flatMap(asInt).filter(_ > 0) openOr 10
        rec <- env.findByTag("sub", Full(tpe)).take(max)
        val title = rec.findData(MetadataKey("sub")).flatMap(_.asNodeSeq) openOr Text("Happening")
        val desc = rec.findData(MetadataKey("desc")).flatMap(_.asNodeSeq)
      } yield (rec, title, desc)

    if ((in \\ "title").filter{
      case e: Elem => e.prefix == "sub"
      case _ => false
    }.isDefined) {
      info.flatMap{ case (rec, title, desc) =>
    Helpers.bind("sub", in,
      "title" -> title, "desc" -> desc,
      AttrBindParam("href", Text(rec.findData("redirect").flatMap(_.asString) openOr env.computeLink(rec)), "href"))
      }
    } else {
      ("*" #> info.map{
        case (rec, title, desc) => "@title *" #> title &
          "@desc *" #> desc &
          "a [href]" #> (rec.findData("redirect").flatMap(_.asString) openOr env.computeLink(rec))
      }).apply(in)
    }
  }

  def doChoose(in: NodeSeq): NodeSeq = {
    val num = S.attr("cnt").flatMap(Helpers.asInt) openOr 1


    val in2 =
      S.session.map(session => session.processSurroundAndInclude("stuff", in)) openOr in

    val lst: List[Elem] = in2.toList.collect {
      case e: Elem => e
    }

    val ids = lst.map(ignore => Helpers.nextFuncName)

    val refresh = S.attr("refresh").flatMap(Helpers.asInt(_)).map(i => if (i < 500) i * 1000 else i)

    val lst2 = lst.zip(ids).map{
      case (elem, id) => elem % ("id" -> id) % ("style" -> "display:none")
    }
    val varName = Helpers.nextFuncName

    lst2 ++ Script(JsCmds.Run(varName+" = ["+ids.map(_.encJs).mkString(",")+"];\n"+
      varName+"_func = function() {"+
      """

        for (var i = 0; i < """+(lst.length * 10)+"""; i++) {
          var tmp = """+varName+""";
          var len = tmp.length;
          var x = Math.floor(Math.random() * len);
          var y = Math.floor(Math.random() * len);
          var q = tmp[x];
          tmp[x] = tmp[y];
          tmp[y] = q;
        };

        for (var i = 0; i < """+varName+""".length; i++) {
          document.getElementById("""+varName+"""[i]).style.display = "none";
        }

        for (var i = 0; i < """+num+"""; i++) {
          document.getElementById("""+varName+"""[i]).style.display = "";
        };
        """+refresh.map(s => "setTimeout('"+varName+"_func()', "+s+");").openOr("")+"""
        };

        """ + varName + """_func();
                                """.stripMargin) )
  }

  def pageInfo: NodeSeq => NodeSeq = ns => {
    val name = S.attr("name") or S.attr("info")

    val data: Box[NodeSeq] = name.flatMap(CurrentFile.value.findData(_)).flatMap(data =>
      data.asListString.map(a => Text(a.mkString(", ")): NodeSeq) or
      data.asNodeSeq or data.asString.map(a => Text(a): NodeSeq) or
    data.asDate.map(d => Text(DateTimeFormat.longDate().print(d)): NodeSeq))

    ns match {
      case e: Elem => ("* *" #> data).apply(e)
      case x => data  openOr NodeSeq.Empty
    }
  }

  def snippets: PartialFunction[(String, String), Box[NodeSeq => NodeSeq]] = {
    val m = Map(("surround", "render") -> Full(Surround.render _),
      ("ignore", "render") -> Full(Ignore.render _),
      ("tail", "render") -> Full(Tail.render _),
      ("head", "render") -> Full(Head.render _),
      ("a", "render") -> Full(doA),
      ("choose", "render") -> Full(doChoose _),
      ("subs", "render") -> Full(doSubs _),
      ("xmenu", "render") -> Full(doXmenu),
      ("bind", "render") -> Full(doBind),
      ("menu", "title") -> Full(HoistedEnvironmentManager.value.menuTitle),
      ("menu", "items") -> Full(HoistedEnvironmentManager.value.menuItems),
      ("site", "name") -> Full(HoistedEnvironmentManager.value.siteName),
      ("title", "render") -> Full(doTitle _),
      ("withparam", "render") -> Full(WithParam.render _),
      ("embed", "render") -> Full(Embed.render _),
      ("if", "render") -> Full(testAttr _),
      ("xform", "render") -> Full(xform),
      ("page-info", "render") -> Full(pageInfo),
      ("page_info", "render") -> Full(pageInfo),
      ("pageinfo", "render") -> Full(pageInfo),
      ("blog", "posts") -> Full(blogPosts),
      ("bootstraputil", "headcomment") -> Full((ignore: NodeSeq) => BootstrapUtil.headComment),
      ("bootstraputil", "bodycomment") -> Full((ignore: NodeSeq) => BootstrapUtil.bodyComment)
    ).withDefaultValue(Empty)

    new PartialFunction[(String, String), Box[NodeSeq => NodeSeq]] {
      def isDefinedAt(in: (String, String)) = true

      def apply(in: (String, String)) = {
        m.apply(in._1.toLowerCase -> in._2.toLowerCase)
      }
    }
  }

  def testAttr(in: NodeSeq): NodeSeq = {
    for {
      toTest <- S.attr("toTest").toList
      attr <- S.attr(toTest).toList if attr == "true"
      node <- in
    } yield node
  }

  object BootstrapUtil {
    def headComment = Unparsed( """    <!-- Le HTML5 shim, for IE6-8 support of HTML elements -->
                                   <!--[if lt IE 9]>
                                     <script src="https://html5shim.googlecode.com/svn/trunk/html5.js"></script>
                                   <![endif]-->""")

    def bodyComment = Unparsed( """  <!--[if IE]>
                        <link rel="stylesheet" type="text/css" href="/css/custom-theme/jquery.ui.1.8.16.ie.css"/>
                        <![endif]-->""")
  }

  def runTemplater(f: ParsedFile, templates: TemplateLookup): ParsedFile = {
    val lu = new PartialFunction[(Locale, List[String]), Box[NodeSeq]] {
      def isDefinedAt(in: (Locale, List[String])): Boolean = {

        true
      }

      def apply(in: (Locale, List[String])): Box[NodeSeq] = {
        lazy val html = if (templates.isDefinedAt((in._2, "html"))) {
          val ret = templates((in._2, "html"))
          ret match {
            case h: HasHtml => Full(h.html)
            case _ => Empty
          }
        } else {
          Empty
        }

        lazy val markdown =
          if (templates.isDefinedAt((in._2, "md"))) {
            val ret = templates((in._2, "md"))
            ret match {
              case h: HasHtml => Full(h.html)
              case _ => Empty
            }
          } else {
            Empty
          }

        lazy val xml =
          if (templates.isDefinedAt((in._2, "xml"))) {
            val ret = templates((in._2, "xml"))
            ret match {
              case h: HasHtml => Full(h.html)
              case _ => Empty
            }
          } else {
            Empty
          }

        lazy val xml_cms =
          if (templates.isDefinedAt((in._2, "cms.xml"))) {
            val ret = templates((in._2, "cms.xml"))
            ret match {
              case h: HasHtml if HoistedEnvironmentManager.value.isHtml(ret) => Full(h.html)
              case _ => Empty
            }
          } else {
            Empty
          }

        html or markdown or xml or xml_cms
      }
    }

    def localSnippets = snippets
    val session = new LiftSession("", Helpers.nextFuncName, Empty) with StatelessSession {
      override def stateful_? = false
    }

    def insureChrome(todo: ParsedFile with HasMetaData, node: NodeSeq): NodeSeq = {

      val _processed = if ((node \\ "html" \\ "body").length > 0) node
      else {
        val templateName = env.chooseTemplateName(todo)
        val res = session.processSurroundAndInclude("Chrome", <lift:surround with={templateName} at="content">
          {node}
        </lift:surround>)
        res
      }

      val processed = PostPageTransforms.get.foldLeft(_processed)((ns, f) => f(ns))

      session.merge(processed, Req.nil)
    }

    S.initIfUninitted(session) {
      LiftRules.autoIncludeAjaxCalc.doWith(() => ignore => false) {
        LiftRules.allowParallelSnippets.doWith(() => false) {
          LiftRules.allowAttributeSnippets.doWith(() => false) {
            LiftRules.snippetWhiteList.doWith(() => localSnippets) {
              LiftRules.externalTemplateResolver.doWith(() => () => lu) {
                CurrentFile.doWith(f) {
                  f match {
                    case todo: ParsedFile with HasHtml with HasMetaData if HoistedEnvironmentManager.value.isHtml(todo) =>
                      HtmlFile(todo.fileInfo,
                        insureChrome(todo,
                          session.processSurroundAndInclude(todo.fileInfo.pureName, todo.html)),
                        todo.metaData, todo.uniqueId)
                    case d => d
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  def fileInfo(inDir: File)(f: File): FileInfo = {
    val cp: String = f.getAbsolutePath().substring(inDir.getAbsolutePath.length)
    val pureName = f.getName
    val dp = pureName.lastIndexOf(".")
    val (name, suf) = if (dp <= 0) (pureName, None)
    else if (pureName.toLowerCase.endsWith(".cms.xml"))
      (pureName.substring(0, pureName.length - 8), Some("cms.xml"))
    else (pureName.substring(0, dp),
      Some(pureName.substring(dp + 1)))
    FileInfo(f, cp, name, pureName, suf)
  }

  def byName(in: Seq[ParsedFile]): Map[String, List[ParsedFile]] = {
    in.foldLeft[Map[String, List[ParsedFile]]](Map.empty) {
      (m, f) =>
        val name = f.fileInfo.name

        m + (name -> (f :: m.getOrElse(name, Nil)))
    }
  }

  def byPureName(in: Seq[ParsedFile]): Map[String, List[ParsedFile]] = {
    in.foldLeft[Map[String, List[ParsedFile]]](Map.empty) {
      (m, f) =>
        val name = f.fileInfo.pureName

        m + (name -> (f :: m.getOrElse(name, Nil)))
    }
  }

  def deleteAll(f: File) {
    if (f.isDirectory()) {
      f.listFiles().foreach(deleteAll)
      f.delete()
    } else f.delete()
  }

  def allFiles(dir: File, filter: File => Boolean): List[File] = {
    if (!filter(dir)) Nil
    else if (dir.isDirectory()) {
      dir.listFiles().toList.flatMap(allFiles(_, filter))
    } else if (dir.isFile() && !dir.getName.startsWith(".")) List(dir)
    else Nil
  }


}

final case class HoistedTransformMetaData()

final case class FileInfo(file: File, relPath: String, name: String, pureName: String, suffix: Option[String]) {
  lazy val pathAndSuffix: PathAndSuffix =
    PathAndSuffix(relPath.toLowerCase.roboSplit("/").dropRight(1) ::: List(name.toLowerCase), suffix.map(_.toLowerCase))
}
