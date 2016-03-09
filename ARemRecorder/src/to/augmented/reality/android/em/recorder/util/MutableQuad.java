package to.augmented.reality.android.em.recorder.util;

public class MutableQuad<T1, T2, T3, T4>
//====================================
{
   public T1 one;
   public T2 two;
   public T3 three;
   public T4 four;

   public MutableQuad(T1 one, T2 two, T3 three, T4 four)
   {
      this.one = one;
      this.two = two;
      this.three = three;
      this.four = four;
   }
}