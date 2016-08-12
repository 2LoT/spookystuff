package com.tribbloids.spookystuff.session

import org.openqa.selenium.{NoSuchSessionException, WebDriver}
import org.slf4j.LoggerFactory

/**
 *
 */
object Clean {

}

trait Clean {

  def clean(): Unit

  override def finalize(): Unit = {
    try {
      clean()
    }
    catch {
      case e: NoSuchSessionException => //already cleaned before
      case e: Throwable =>
        LoggerFactory.getLogger(this.getClass).warn("!!!!! FAIL TO CLEANE UP DRIVER !!!!!"+e)
    }
    finally {
      super.finalize()
    }
  }
}


trait CleanWebDriverMixin extends Clean {
  this: WebDriver =>

  def clean(): Unit = {
    this.close()
    this.quit()
  }
}