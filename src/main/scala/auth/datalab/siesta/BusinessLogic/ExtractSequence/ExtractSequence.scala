package auth.datalab.siesta.BusinessLogic.ExtractSequence

import auth.datalab.siesta.BusinessLogic.Model.Structs
import auth.datalab.siesta.Utils.Utilities

object ExtractSequence {


  def combineSequences(x: List[Structs.Event], y: List[Structs.Event]): List[Structs.Event] = {
    (x, y) match {
      case (Nil, Nil) => Nil
      case (_ :: _, Nil) => x
      case (Nil, _ :: _) => y
      case (i :: _, j :: _) =>
        if (Utilities.compareTimes(i.timestamp, j.timestamp))
          i :: combineSequences(x.tail, y)
        else
          j :: combineSequences(x, y.tail)
    }
  }


}