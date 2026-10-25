package com.bitchat.android.cascade.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.bitchat.android.R
import androidx.compose.animation.core.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.luminance
import kotlin.math.sin

val Ink: Color @Composable get()=MaterialTheme.colorScheme.onSurface
val Paper: Color @Composable get()=MaterialTheme.colorScheme.surface
val Blue: Color @Composable get()=MaterialTheme.colorScheme.primary
val Signal: Color @Composable get()=MaterialTheme.colorScheme.secondaryContainer
val Green: Color @Composable get()=MaterialTheme.colorScheme.primaryContainer
val Yellow: Color @Composable get()=MaterialTheme.colorScheme.surfaceVariant
val Pink: Color @Composable get()=MaterialTheme.colorScheme.secondaryContainer
val Display=FontFamily(Font(R.font.space_grotesk))
val Mono=FontFamily(Font(R.font.jetbrains_mono))

@Composable fun CascadeTheme(dark: Boolean,content: @Composable () -> Unit) {
    val colors=if(dark) darkColorScheme(primary=Color(0xFF6CFF85),onPrimary=Color(0xFF031007),background=Color(0xFF050806),surface=Color(0xFF0C120E),onSurface=Color(0xFFDCEBDD),onBackground=Color(0xFFDCEBDD),primaryContainer=Color(0xFF14291B),onPrimaryContainer=Color(0xFFDCEBDD),secondaryContainer=Color(0xFF203626),onSecondaryContainer=Color(0xFFDCEBDD),surfaceVariant=Color(0xFF17241C),onSurfaceVariant=Color(0xFFB1C6B6),outline=Color(0xFF3C5242),secondary=Color(0xFF9ED7A9))
        else lightColorScheme(primary=Color(0xFF202124),onPrimary=Color.White,background=Color(0xFFF8F9F7),surface=Color.White,onSurface=Color(0xFF202124),onBackground=Color(0xFF202124),primaryContainer=Color(0xFFE9EDE9),onPrimaryContainer=Color(0xFF202124),secondaryContainer=Color(0xFFE4E9E4),onSecondaryContainer=Color(0xFF202124),surfaceVariant=Color(0xFFEFF2EE),onSurfaceVariant=Color(0xFF515A53),outline=Color(0xFFB7C0B8),secondary=Color(0xFF46564B))
    MaterialTheme(colorScheme=colors,typography=Typography(
        bodyLarge=androidx.compose.ui.text.TextStyle(fontFamily=Display,fontSize=16.sp,lineHeight=23.sp),
        titleLarge=androidx.compose.ui.text.TextStyle(fontFamily=Display,fontSize=25.sp,fontWeight=FontWeight.Bold),
        labelLarge=androidx.compose.ui.text.TextStyle(fontFamily=Mono,fontSize=12.sp,fontWeight=FontWeight.Bold)
    ),shapes=Shapes(extraSmall=RoundedCornerShape(8.dp),small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(18.dp),large=RoundedCornerShape(24.dp),extraLarge=RoundedCornerShape(28.dp)),content=content)
}

@Composable fun Modifier.hardPanel(color: Color) = this.background(color,RoundedCornerShape(20.dp)).border(1.dp,MaterialTheme.colorScheme.outline.copy(alpha=.45f),RoundedCornerShape(20.dp))

@Composable fun HalftoneBackground(enabled: Boolean,modifier: Modifier=Modifier) {
    val ink=MaterialTheme.colorScheme.primary
    var phase=0f
    if(enabled && android.animation.ValueAnimator.areAnimatorsEnabled()) {
        val transition=rememberInfiniteTransition(label="halftone")
        val animated by transition.animateFloat(0f,6.28318f,infiniteRepeatable(tween(16000,easing=LinearEasing)),label="dot drift")
        phase=animated
    }
    Canvas(modifier.clearAndSetSemantics {}) {
        val spacing=20.dp.toPx()
        for(x in 0..(size.width/spacing).toInt()) for(y in 0..(size.height/spacing).toInt()) {
            val wave=(sin(x*.4f+y*.25f-phase)+1f)/2f
            drawCircle(ink.copy(alpha=.035f+wave*.045f),(0.55f+wave*.65f).dp.toPx(),Offset(x*spacing,y*spacing))
        }
    }
}

@Composable fun AsciiSignal(text: String="[YOU] -- [PEER] -- [HELP]\n   \\_____ MESH _____/") {
    Text(text,Modifier.clearAndSetSemantics {},fontFamily=Mono,fontSize=10.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.primary.copy(alpha=.75f))
}

@Composable fun Panel(color: Color=MaterialTheme.colorScheme.surface,content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().hardPanel(color).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
}
@Composable fun Label(text: String,color: Color=MaterialTheme.colorScheme.onSurface) {
    Text(text.uppercase(),fontFamily=Mono,fontSize=11.sp,fontWeight=FontWeight.Bold,color=color,letterSpacing=0.7.sp)
}
@Composable fun Headline(kicker: String,title: String,description: String?=null) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Label(kicker,Blue)
        Text(title.lowercase().replaceFirstChar(Char::uppercase),fontFamily=Display,fontSize=34.sp,fontWeight=FontWeight.Medium,lineHeight=39.sp,letterSpacing=(-1).sp)
        if(description!=null) Text(description,fontSize=14.sp)
        AsciiSignal()
    }
}
@Composable fun Action(text: String,color: Color=Blue,enabled: Boolean=true,onClick: () -> Unit) {
    Button(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),shape=RoundedCornerShape(28.dp),contentPadding=PaddingValues(horizontal=16.dp,vertical=15.dp),
        colors=ButtonDefaults.buttonColors(containerColor=color,contentColor=if(color==Blue || color==Ink) Paper else Ink)) {
        Text(text.lowercase().replaceFirstChar(Char::uppercase),fontFamily=Display,fontSize=15.sp,fontWeight=FontWeight.Medium)
    }
}
@Composable fun Badge(text: String,color: Color=Green) {
    Text(text.uppercase(),Modifier.background(color,RoundedCornerShape(50)).padding(horizontal=9.dp,vertical=5.dp),color=if(color.luminance()>.25f) Color(0xFF202124) else Ink,fontFamily=Mono,fontSize=9.sp,fontWeight=FontWeight.Medium)
}
@Composable fun Input(value: String,label: String,onChange: (String)->Unit,lines: Int=1) {
    OutlinedTextField(value=value,onValueChange=onChange,label={ Text(label.uppercase(),fontFamily=Mono,fontSize=10.sp) },modifier=Modifier.fillMaxWidth().heightIn(min=60.dp),minLines=lines,singleLine=lines==1,shape=RoundedCornerShape(12.dp))
}
@Composable fun Toggle(title: String,value: Boolean,onChange: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Text(title.uppercase(),Modifier.weight(1f),fontFamily=Mono,fontSize=12.sp)
        Switch(checked=value,onCheckedChange=onChange)
    }
}
fun urgencyColor(value: Int) = when(value) { 5->Color(0xFF7D9282); 4->Color(0xFF879C8C); 3->Color(0xFF92A997); 2->Color(0xFFA5B8A9); else->Color(0xFFB8CDBD) }

@Composable fun EmptyState(title: String,body: String) {
    Panel { Label("NO SIGNAL YET"); Text(title,fontSize=23.sp,fontWeight=FontWeight.Bold); Text(body,fontSize=14.sp) }
}
