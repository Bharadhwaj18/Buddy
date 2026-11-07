package com.shrey.buddy.ui.money

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalGroceryStore
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Work
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.shrey.buddy.finance.Category
import com.shrey.buddy.ui.theme.BuddyColors

val Category.icon: ImageVector
    get() = when (this) {
        Category.FOOD -> Icons.Rounded.Restaurant
        Category.GROCERIES -> Icons.Rounded.LocalGroceryStore
        Category.SHOPPING -> Icons.Rounded.ShoppingBag
        Category.TRANSPORT -> Icons.Rounded.DirectionsCar
        Category.FUEL -> Icons.Rounded.LocalGasStation
        Category.TRAVEL -> Icons.Rounded.Flight
        Category.BILLS -> Icons.AutoMirrored.Rounded.ReceiptLong
        Category.SUBSCRIPTIONS -> Icons.Rounded.Autorenew
        Category.HEALTH -> Icons.Rounded.FavoriteBorder
        Category.ENTERTAINMENT -> Icons.Rounded.Movie
        Category.EDUCATION -> Icons.Rounded.School
        Category.RENT -> Icons.Rounded.Home
        Category.PEOPLE -> Icons.Rounded.Person
        Category.INVESTMENTS -> Icons.AutoMirrored.Rounded.TrendingUp
        Category.FEES -> Icons.Rounded.AccountBalance
        Category.OTHER -> Icons.Rounded.Payments
        Category.SALARY -> Icons.Rounded.Work
        Category.INCOME -> Icons.Rounded.SouthWest
        Category.REFUNDS -> Icons.Rounded.Replay
        Category.TRANSFER -> Icons.Rounded.SwapHoriz
    }

val Category.color: Color
    get() = when (this) {
        Category.FOOD -> Color(0xFFFF8A4C)
        Category.GROCERIES -> Color(0xFF7BD88F)
        Category.SHOPPING -> Color(0xFFFF6FAE)
        Category.TRANSPORT -> Color(0xFF5AC8FA)
        Category.FUEL -> Color(0xFFFFA94D)
        Category.TRAVEL -> Color(0xFF64D2FF)
        Category.BILLS -> Color(0xFFFFD60A)
        Category.SUBSCRIPTIONS -> Color(0xFFA78BFA)
        Category.HEALTH -> Color(0xFFFF6B6B)
        Category.ENTERTAINMENT -> Color(0xFFBF5AF2)
        Category.EDUCATION -> Color(0xFF30D5C8)
        Category.RENT -> Color(0xFFD4A373)
        Category.PEOPLE -> Color(0xFF8E9AAF)
        Category.INVESTMENTS -> Color(0xFF34C759)
        Category.FEES -> Color(0xFFB0B4BC)
        Category.OTHER -> BuddyColors.TextSecondary
        Category.SALARY, Category.INCOME, Category.REFUNDS -> BuddyColors.Lime
        Category.TRANSFER -> BuddyColors.TextTertiary
    }
