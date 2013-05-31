package cc.factorie.app.nlp.segment

import cc.factorie.app.nlp.{DocumentAnnotator, Token, Document}
import cc.factorie.app.strings.RegexSegmenter

// TODO: this still needs testing on more text, contractions, and probably simplification --brian

// TODO: Gather abbreviations in separate Collection[String], and remove "may\\." from the collection. 

// TODO: Rename to RegexTokenizer

/** Split a String into Tokens.  Aims to adhere to CoNLL 2003 tokenization rules.
    Punctuation that ends a sentence should be placed alone in its own Token, hence this segmentation implicitly defines sentence segmentation also.
    @author martin 
    */
// TODO Rename this to RegexTokenizer and make it a DocumentAnnotator
class RegexTokenizer extends RegexSegmenter(Seq(
  "'[tT]|'[lL]+|'[sS]|'[mM]|'re|'RE|'ve|'VE", // this isn't working as expected
  "[\\p{L}]\\.[\\p{L}\\.]*", // A.de, A.A.A.I, etc.
  "[0-9]{1,2}[sthnrd]+[\\-\\p{L}]+", // the second part is for 20th-century
  "[0-9\\-.\\:/,\\+\\=%]+[0-9\\-:/,\\+\\=%]", // is there a better way to say, "doesn't end in '.'"?
  "[\\p{L}\\p{Nd}.]+@[\\p{L}\\{Nd}\\.]+\\.[a-z]{2,4}", // email
  "[A-Z][a-z]?\\.", // Mr. Mrs. Calif. but not Institute.
  "inc\\.","corp\\.","dec\\.","jan\\.","feb\\.","mar\\.","apr\\.","jun\\.","jul\\.","aug\\.","sep\\.","oct\\.","nov\\.","ala\\.","ariz\\.","ark\\.","colo\\.","conn\\.","del\\.","fla\\.","ill\\.","ind\\.","kans\\.","kan\\.","ken\\.","kent\\.","mass\\.","mich\\.","minn\\.","miss\\.","mont\\.","nebr\\.","neb\\.","nev\\.","dak\\.","okla\\.","oreg\\.","tenn\\.","tex\\.","virg\\.","wash\\.","wis\\.","wyo\\.","mrs\\.","calif\\.","oct\\.","vol\\.","rev\\.","ltd\\.","dea\\.","est\\.","capt\\.","hev\\.","gen\\.","ltd\\.","etc\\.","sci\\.","comput\\.","univ\\.","ave\\.","cent\\.","col\\.","comdr\\.","cpl\\.","dept\\.","dust,","div\\.","est\\.","gal\\.","gov\\.","hon\\.","grad\\.","inst\\.","lib\\.","mus\\.","pseud\\.","ser\\.","alt\\.","Inc\\.","Corp\\.","Dec\\.","Jan\\.","Feb\\.","Mar\\.","Apr\\.","May\\.","Jun\\.","Jul\\.","Aug\\.","Sep\\.","Oct\\.","Nov\\.","Ala\\.","Ariz\\.","Ark\\.","Colo\\.","Conn\\.","Del\\.","Fla\\.","Ill\\.","Ind\\.","Kans\\.","Kan\\.","Ken\\.","Kent\\.","Mass\\.","Mich\\.","Minn\\.","Miss\\.","Mont\\.","Nebr\\.","Neb\\.","Nev\\.","Dak\\.","Okla\\.","Oreg\\.","Tenn\\.","Tex\\.","Virg\\.","Wash\\.","Wis\\.","Wyo\\.","Mrs\\.","Calif\\.","Oct\\.","Vol\\.","Rev\\.","Ltd\\.","Dea\\.","Est\\.","Capt\\.","Hev\\.","Gen\\.","Ltd\\.","Etc\\.","Sci\\.","Comput\\.","Univ\\.","Ave\\.","Cent\\.","Col\\.","Comdr\\.","Cpl\\.","Dept\\.","Dust,","Div\\.","Est\\.","Gal\\.","Gov\\.","Hon\\.","Grad\\.","Inst\\.","Lib\\.","Mus\\.","Pseud\\.","Ser\\.","Alt\\.",
  "[.?!][\\p{Pf}\\p{Pe}]?", // ending/final punctuation
  "[\\p{Pf}\\p{Pe}]?[.?!]", // ending/final punctuation followed by [.?!]
  "[`'\"]+", // mid-sentence quotes
  """[,-:;$?&@\(\)]+""", // other symbols
  "[\\w\\-0-9]+(?='([tT]|[lL]+|[sS]|[mM]|re|RE|ve|VE))", // any combo of word-chars, numbers, and hyphens
  "[\\w0-9]+(-[\\w0-9]+)*", // words with sequences of single dashes in them
  "[\\w0-9']+" // any combo of word-chars, numbers, and hyphens
).mkString("|").r) with DocumentAnnotator {

  def process1(document: Document): Document = {
    for (section <- document.sections) {
      val tokenIterator = RegexTokenizer.this.apply(section.string)
      while (tokenIterator.hasNext) {
        tokenIterator.next()
        new Token(section, tokenIterator.start, tokenIterator.end)
      }
    }
    document
  }
  def prereqAttrs: Iterable[Class[_]] = Nil
  def postAttrs: Iterable[Class[_]] = List(classOf[Token])
}

object RegexTokenizer extends RegexTokenizer {
  def main(args: Array[String]): Unit = {
    val string = io.Source.fromFile(args(0)).mkString
    val doc = new Document(string)
    RegexTokenizer.process(doc)
    println(doc.tokens.map(_.string).mkString("\n"))
  }
}