package org.incept5.example

import org.incept5.error.CoreException
import org.incept5.error.Error
import org.incept5.error.ErrorCategory
import jakarta.inject.Singleton

@Singleton
class ExampleService {

    companion object {
        val names = mutableSetOf<String>()
    }

    fun createExample(command: ExampleCommand): ExampleResult {
        if (names.contains(command.name)) {
            throw CoreException(
                category = ErrorCategory.VALIDATION,
                errors = listOf(Error("name_already_exists", "name")),
                message = "Name already exists"
            )
        }

        if ( command.name.contains("error") ) {
            throw CoreException(
                category = ErrorCategory.VALIDATION,
                errors = listOf(Error("name_error", "name")),
                message = "erroneous name was ${command.name}"
            )
        }

        names.add(command.name)
        return ExampleResult(command.name)
    }

}
