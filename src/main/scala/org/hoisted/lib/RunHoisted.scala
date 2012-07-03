package org.hoisted
package lib

import net.liftweb._
import common._
import http._
import util._
import Helpers._
import java.util.Locale
import java.io.{FileWriter, FileOutputStream, FileInputStream, File}
import xml._
import org.joda.time.format.DateTimeFormat


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

  def env = HoistedEnvironmentManager.value

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
            LiftRules.snippetWhiteList.doWith(() => env.snippets) {
              LiftRules.externalTemplateResolver.doWith(() => () => lu) {
                CurrentFile.doWith(f) {
                  env.beginRendering(f)
                  try {
                    f match {
                      case todo: ParsedFile with HasHtml with HasMetaData if HoistedEnvironmentManager.value.isHtml(todo) =>
                        HtmlFile(todo.fileInfo,
                          insureChrome(todo,
                            session.processSurroundAndInclude(todo.fileInfo.pureName, todo.html)),
                          todo.metaData, todo.uniqueId)
                      case d => d
                    }
                  } finally {
                    env.endRendering(f)
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

