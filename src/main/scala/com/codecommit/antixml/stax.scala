package com.codecommit.antixml

import org.xml.sax.{Attributes, ContentHandler}
import org.xml.sax.helpers.AttributesImpl
import java.io.{InputStream, StringReader, Reader}
import javax.xml.namespace.{NamespaceContext, QName}
import javax.xml.stream.{XMLInputFactory, XMLStreamException}
import javax.xml.stream.XMLStreamConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.XMLConstants.NULL_NS_URI

/**
 * An XML provider build on top of StAXIterator.
 */
class StAXParser extends XML {
  import StAXEvents._
  
  override def fromInputStream(inputStream: InputStream): Elem =
    fromStreamSource(new StreamSource(inputStream))
  
  override def fromReader(reader: Reader): Elem =
    fromStreamSource(new StreamSource(reader))
  
  override def fromString(xml: String): Elem =
    fromReader(new StringReader(xml))
  
  def fromStreamSource(source: StreamSource): Elem =
    parse(new StAXIterator(source))

  def parse(source: Iterator[StAXEvent]): Elem = {
    import XMLStreamConstants.{CDATA => CDATAFlag, CHARACTERS,
    END_ELEMENT, START_ELEMENT, ENTITY_REFERENCE}
    val handler = new NodeSeqSAXHandler()
    while(source.hasNext) {
      val event = source.next
      event.number match {
        // this match is very active during parsing
        // the ugliness is to ensure it compiles to a tablelookup instead
        // of if-elses
        case `START_ELEMENT` => {
          val elemStart = event.asInstanceOf[ElemStart]
          handler.startElement(elemStart.uri getOrElse "",
                               elemStart.name,
                               elemStart.prefix map ((_: String) + ":" + elemStart.name) getOrElse "",
                               mapToAttrs(elemStart.attrs))
        }
        case `END_ELEMENT` => {
          val elemEnd = event.asInstanceOf[ElemEnd]
          handler.endElement(elemEnd.prefix getOrElse "",
                             elemEnd.name,
                             elemEnd.prefix map ((_: String) + ":" + elemEnd.name) getOrElse "")
        }
        case `CHARACTERS` => {
          val characters = event.asInstanceOf[Characters]
          handler.characters(characters.text.toArray, 0, characters.text.length)
        }
        case `ENTITY_REFERENCE` => {
          val entityRef = event.asInstanceOf[EntityRef]
          handler.skippedEntity(entityRef.text)
        }
        case `CDATAFlag` => {
          val cdata = event.asInstanceOf[CDATA]
          handler.startCDATA()
          handler.characters(cdata.text.toArray, 0, cdata.text.length)
          handler.endCDATA()
        }
        case _ => ()
      }
    }
    handler.result().head   // safe because anything else won't validate
  }
  
  /**
   * Returns an equivalent Attributes for a Map of QNames to Strings.
   * @return an equivalent Attributes for a Map of QNames to Strings.
   */
  private def mapToAttrs(attrs: Map[QName, String]): Attributes = {
    val result = new AttributesImpl()
    attrs foreach { case (qname, value) =>
      result.addAttribute(qname.getNamespaceURI,
                          qname.getLocalPart,
                          qname.toString,
                          "",
                          value)
    }
    result
  }
}

object StAXIterator {
  def fromString(xml: String): Iterator[StAXEvents.StAXEvent] =
    new StAXIterator(new StreamSource(new StringReader(xml)))
}
/**
 * An Iterator over StAXEvents. This implementation uses the
 * <a href="http://download.oracle.com/javase/6/docs/api/javax/xml/stream/package-summary.html">
 * Java Streaming API for XML</a>.
 * @see java.xml.stream
 */
private[antixml] class StAXIterator(source: StreamSource) extends Iterator[StAXEvents.StAXEvent] {
  import StAXEvents._
  import XMLStreamConstants.{CDATA => CDATAFlag, CHARACTERS, COMMENT, DTD,
    END_ELEMENT, END_DOCUMENT, PROCESSING_INSTRUCTION, START_ELEMENT, ENTITY_REFERENCE}
  
  private val xmlReader =
    XMLInputFactory.newInstance().createXMLStreamReader(source)
  override def next: StAXEvent = xmlReader.next match {
    case `CDATAFlag` => CDATA(xmlReader.getText)
    case `CHARACTERS` => Characters(xmlReader.getText)
    case `COMMENT` => Comment(xmlReader.getText)
    case `DTD` => DocumentTypeDefinition(xmlReader.getText)
    case `END_ELEMENT` =>
      ElemEnd(stringToOption(xmlReader.getPrefix),
                          xmlReader.getLocalName,
                          stringToOption(xmlReader.getNamespaceURI))
    case `END_DOCUMENT` => DocumentEnd
    case `PROCESSING_INSTRUCTION` =>
      ProcessingInstruction(xmlReader.getPITarget, xmlReader.getPIData)
    case `START_ELEMENT` => {
      val attrs =
        (Map.empty[QName, String] /: (0 until xmlReader.getAttributeCount)) { (attrs, i) =>
          attrs + (xmlReader.getAttributeName(i) -> xmlReader.getAttributeValue(i))
        }
      ElemStart(stringToOption(xmlReader.getPrefix),
                xmlReader.getLocalName,
                attrs,
                stringToOption(xmlReader.getNamespaceURI))
    }
    case `ENTITY_REFERENCE` =>
      EntityRef(xmlReader.getLocalName, xmlReader.getText)
    case _ =>
      throw new XMLStreamException("Unexpected StAX event of type " +
                                   xmlReader.getEventType)
  }
  override def hasNext: Boolean = xmlReader.hasNext

  /**
   * Returns Some string if string is not null or empty.
   * @return Some string if string is not null or empty.
   */
  @inline private def stringToOption(string: String): Option[String] =
    string match {
      case null => None
      case "" => None
      case string => Some(string)
    }
}

object StAXEvents {
  import XMLStreamConstants.{CDATA => CDATAFlag, CHARACTERS, COMMENT, DTD, END_ELEMENT, END_DOCUMENT, PROCESSING_INSTRUCTION, START_ELEMENT, ENTITY_REFERENCE}

  /**
   * A base trait for StAXParser generated events.
   * @see java.xml.stream.XMLEvent
   * */
  abstract class StAXEvent(val number: Int)
  object ElemStart {
    def apply(name: String, attrs: Map[QName, String]): ElemStart =
      ElemStart(None, name, attrs, None)
  }
  /**
   * A StAXEvent indicating the start of an Elem.
   */
  case class ElemStart(prefix: Option[String],
                       name: String,
                       attrs: Map[QName, String],
                       uri: Option[String]) extends StAXEvent(START_ELEMENT) {
    def attrs(name: String): String = attrs(new QName(NULL_NS_URI, name))
  }
  /**
   * A StAXEvent indicating the end of an Elem.
   */
  case class ElemEnd(prefix: Option[String],
                     name: String,
                     uri: Option[String]) extends StAXEvent(END_ELEMENT)
  /**
   * A StAXEvent indicating a text Node.
   */
  case class Characters(text: String) extends StAXEvent(CHARACTERS)
  /**
   * A StAXEvent indicating a CDATA Node.
   */
  case class CDATA(text: String) extends StAXEvent(CDATAFlag)
  /**
   * A StAXEvent indicating a comment Node.
   */
  case class Comment(text: String) extends StAXEvent(COMMENT)
  /**
   * A StAXEvent indicating a processing instruction Node.
   */
  case class ProcessingInstruction(target: String, data: String) extends StAXEvent(PROCESSING_INSTRUCTION)
  /**
   * A StAXEvent indicating a DocumentTypeDefinition (DTD).
   */
  case class DocumentTypeDefinition(declaration: String) extends StAXEvent(DTD)
  case class EntityRef(localName: String, text: String) extends StAXEvent(ENTITY_REFERENCE)
  /**
   * A StAXEvent indicating the end of an XML document.
   */
  object DocumentEnd extends StAXEvent(END_DOCUMENT)
}
